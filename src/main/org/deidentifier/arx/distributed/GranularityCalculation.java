package org.deidentifier.arx.distributed;

import org.deidentifier.arx.DataHandle;
import org.deidentifier.arx.aggregates.HierarchyBuilder;
import org.deidentifier.arx.aggregates.HierarchyBuilderRedactionBased;
import org.deidentifier.arx.aggregates.quality.*;
import org.deidentifier.arx.common.Groupify;
import org.deidentifier.arx.common.TupleWrapper;
import org.deidentifier.arx.common.WrappedBoolean;
import org.deidentifier.arx.common.WrappedInteger;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class GranularityCalculation {

    static double calculateLossDirectly(DataHandle output,
                                        QualityConfiguration configuration,
                                        int[] indices,
                                        String[][][] hierarchies,
                                        QualityDomainShare[] shares) {
        QualityModelColumnOrientedLoss qualityModelColumnOrientedLoss = new QualityModelColumnOrientedLoss(new WrappedBoolean(),new WrappedInteger(), 0, null, output,
                0, getSuppressed(output), null, getGroupify(output, indices), hierarchies, shares, indices, configuration);
        return qualityModelColumnOrientedLoss.evaluate().getGranularity();
    }

    /**
     * TODO: Copied from StatisticsQuality
     */
    static int getSuppressed(DataHandle handle) {
        int suppressed = 0;
        for (int row = 0; row < handle.getNumRows(); row++) {
            suppressed += handle.isOutlier(row) ? 1 : 0;
        }
        return suppressed;
    }

    /**
     * TODO: Copied from StatisticsQuality
     */
    static Groupify<TupleWrapper> getGroupify(DataHandle handle, int[] indices) {

        // Prepare
        int capacity = handle.getNumRows() / 10;
        capacity = Math.max(capacity, 10);
        Groupify<TupleWrapper> groupify = new Groupify<>(capacity);
        int numRows = handle.getNumRows();
        for (int row = 0; row < numRows; row++) {
            if (!handle.isOutlier(row)) {
                TupleWrapper tuple = new TupleWrapper(handle, indices, row);
                groupify.add(tuple);
            }
        }

        return groupify;
    }

    /**
     * TODO: Copied from StatisticsQuality
     */
    static int[] getIndicesOfQuasiIdentifiers(Set<String> userdefined, DataHandle handle) {
        int[] result = new int[handle.getDefinition().getQuasiIdentifyingAttributes().size()];
        int index = 0;
        for (String qi : handle.getDefinition().getQuasiIdentifyingAttributes()) {
            if (userdefined == null || userdefined.isEmpty() || userdefined.contains(qi)) {
                result[index++] = handle.getColumnIndexOf(qi);
            }
        }
        Arrays.sort(result);
        return result;
    }

    /**
     * TODO: Copied from StatisticsQuality
     */
    static String[][][] getHierarchies(DataHandle handle,
                                       int[] indices,
                                       QualityConfiguration config) {

        String[][][] hierarchies = new String[indices.length][][];

        // Collect hierarchies
        for (int i=0; i<indices.length; i++) {

            // Extract and store
            String attribute = handle.getAttributeName(indices[i]);
            String[][] hierarchy = handle.getDefinition().getHierarchy(attribute);

            // If not empty
            if (hierarchy != null && hierarchy.length != 0 && hierarchy[0] != null && hierarchy[0].length != 0) {

                // Clone
                hierarchies[i] = hierarchy.clone();

            } else {

                // Create trivial hierarchy
                String[] values = handle.getDistinctValues(indices[i]);
                hierarchies[i] = new String[values.length][2];
                for (int j = 0; j < hierarchies[i].length; j++) {
                    hierarchies[i][j][0] = values[j];
                    hierarchies[i][j][1] = config.getSuppressedValue();
                }
            }
        }

        // Fix hierarchy (if suppressed character is not contained in generalization hierarchy)
        for (int j=0; j<indices.length; j++) {

            // Access
            String[][] hierarchy = hierarchies[j];

            // Check if there is a problem
            Set<String> values = new HashSet<String>();
            for (int i = 0; i < hierarchy.length; i++) {
                String[] levels = hierarchy[i];
                values.add(levels[levels.length - 1]);
            }

            // There is a problem
            if (values.size() > 1) {
                for(int i = 0; i < hierarchy.length; i++) {
                    hierarchy[i] = Arrays.copyOf(hierarchy[i], hierarchy[i].length + 1);
                    hierarchy[i][hierarchy[i].length - 1] = config.getSuppressedValue();
                }
            }

            // Replace
            hierarchies[j] = hierarchy;
        }

        // Return
        return hierarchies;
    }

    /**
     * TODO: Copied from StatisticsQuality
     */
    static QualityDomainShare[] getDomainShares(DataHandle handle,
                                                int[] indices,
                                                String[][][] hierarchies,
                                                QualityConfiguration config) {

        // Prepare
        QualityDomainShare[] shares = new QualityDomainShare[indices.length];

        // Compute domain shares
        for (int i=0; i<shares.length; i++) {

            try {

                // Extract info
                String[][] hierarchy = hierarchies[i];
                String attribute = handle.getAttributeName(indices[i]);
                HierarchyBuilder<?> builder = handle.getDefinition().getHierarchyBuilder(attribute);

                // Create shares for redaction-based hierarchies
                if ((builder instanceof HierarchyBuilderRedactionBased) &&
                        ((HierarchyBuilderRedactionBased<?>) builder).isDomainPropertiesAvailable()){
                    shares[i] = new QualityDomainShareRedaction((HierarchyBuilderRedactionBased<?>)builder);

                    // Create fallback-shares for materialized hierarchies
                    // TODO: Interval-based hierarchies are currently not compatible
                } else {
                    shares[i] = new QualityDomainShareRaw(hierarchy, config.getSuppressedValue());
                }

            } catch (Exception e) {
                // Ignore silently
                shares[i] = null;
            }
        }

        // Return
        return shares;
    }

    /**
     * Calculates granularity losses for a list of data handles.
     *
     * @param handles The list of data handles to be processed.
     * @param configuration Quality configuration used for calculation.
     * @param indices Array of indices of quasi identifying attributes.
     * @param hierarchies Hierarchical data structures for granularity calculations.
     * @param shares
     * @return A list of calculated granularity losses for each data handle.
     */
    public static List<Double> calculateGranularityLosses(List<DataHandle> handles,
                                                          QualityConfiguration configuration,
                                                          int[] indices,
                                                          String[][][] hierarchies,
                                                          QualityDomainShare[] shares) {
        List<Double> granularities = new ArrayList<>();
        for (DataHandle handle : handles) {
            granularities.add(calculateLossDirectly(handle, configuration, indices, hierarchies, shares));
        }
        return granularities;
    }

    /**
     * Calculates granularity losses for a list of data handles concurrently.
     *
     * @param handles The list of data handles to be processed.
     * @param configuration Quality configuration used for calculation.
     * @param indices Array of indices of quasi identifying attributes.
     * @param hierarchies Hierarchical data structures for granularity calculations.
     * @param shares
     * @return A list of calculated granularity losses for each data handle.
     * @throws InterruptedException If the concurrent execution is interrupted.
     * @throws ExecutionException If there's an error during concurrent execution.
     */
    public static List<Double> calculateGranularityLossesConcurrently(List<DataHandle> handles,
                                                    QualityConfiguration configuration,
                                                    int[] indices,
                                                    String[][][] hierarchies,
                                                    QualityDomainShare[] shares)
            throws InterruptedException, ExecutionException {

        List<Future<Double>> futures = new ArrayList<>();
        for (DataHandle handle : handles) {
            UtilityCalculationWorker worker = new UtilityCalculationWorker(handle, configuration, indices, hierarchies, shares);
            futures.add(Executors.newCachedThreadPool().submit(worker));
        }
        return getResults(futures);
    }

    /**
     * Fetches the results from a list of futures.
     *
     * @param <T> Type of the result fetched from the future.
     * @param futures List of futures to fetch results from.
     * @return A list containing results from the provided futures.
     * @throws InterruptedException If the fetching process is interrupted.
     * @throws ExecutionException If there's an error during fetching.
     */
    public static <T> List<T> getResults(List<Future<T>> futures) throws InterruptedException, ExecutionException {
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get());
        }
        return results;
    }

    /**
     * Returns the weighted average of the given values based on the provided weights.
     *
     * @param values List of values to compute the weighted average from.
     * @param weights Corresponding weights for the given values.
     * @return The calculated weighted average.
     */
    public static double getWeightedAverageForGranularities(List<Double> values, List<Double> weights) {
        double result = 0d;
        double weightSum = 0d;
        for (int i = 0; i<values.size(); i++) {
            result += values.get(i) * weights.get(i);
            weightSum += weights.get(i);
        }
        return result / weightSum;
    }
}
