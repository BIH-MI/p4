/*
 * ARX Data Anonymization Tool
 * Copyright 2012 - 2022 Fabian Prasser and contributors
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.deidentifier.arx.distributed;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.deidentifier.arx.*;
import org.deidentifier.arx.ARXConfiguration.Monotonicity;
import org.deidentifier.arx.aggregates.quality.QualityConfiguration;
import org.deidentifier.arx.aggregates.quality.QualityDomainShare;
import org.deidentifier.arx.criteria.EDDifferentialPrivacy;
import org.deidentifier.arx.criteria.ModelWithExchangeableSubset;
import org.deidentifier.arx.criteria.PrivacyCriterion;
import org.deidentifier.arx.exceptions.RollbackRequiredException;

import static org.deidentifier.arx.distributed.GranularityCalculation.*;

/**
 * Distributed anonymizer
 * @author Fabian Prasser
 *
 */
public class ARXDistributedAnonymizer {
    
    /**
     * Distribution strategy
     * @author Fabian Prasser
     */
    public enum DistributionStrategy {
        LOCAL
    }

    /**
     * Partitioning strategy
     * @author Fabian Prasser
     */
    public enum PartitioningStrategy {
        RANDOM,
        SORTED
    }
    
    /**
     * Strategy for defining common transformation levels
     * @author Fabian Prasser
     */
    public enum TransformationStrategy {
        GLOBAL_AVERAGE,
        GLOBAL_MINIMUM,
        LOCAL
    }

    /** O_min */
    private static final double          O_MIN     = 0.05d;

    /** Wait time */
    private static final int             WAIT_TIME = 100;

    /** Number of nodes to use */
    private final int                    nodes;
    /** Partitioning strategy */
    private final PartitioningStrategy   partitioningStrategy;
    /** Distribution strategy */
    private final DistributionStrategy   distributionStrategy;
    /** Distribution strategy */
    private final TransformationStrategy transformationStrategy;
    /** Track memory consumption */
    private final boolean                trackMemoryConsumption;

    /**
     * Creates a new instance
     * @param nodes
     * @param partitioningStrategy
     * @param distributionStrategy
     * @param transformationStrategy
     */
    public ARXDistributedAnonymizer(int nodes,
                                    PartitioningStrategy partitioningStrategy,
                                    DistributionStrategy distributionStrategy,
                                    TransformationStrategy transformationStrategy) {
        this(nodes, partitioningStrategy, distributionStrategy, transformationStrategy, false);
    }
    
    /**
     * Creates a new instance
     * @param nodes
     * @param partitioningStrategy
     * @param distributionStrategy
     * @param transformationStrategy
     * @param trackMemoryConsumption Handle with care. Will negatively impact execution times.
     */
    public ARXDistributedAnonymizer(int nodes,
                                    PartitioningStrategy partitioningStrategy,
                                    DistributionStrategy distributionStrategy,
                                    TransformationStrategy transformationStrategy,
                                    boolean trackMemoryConsumption) {
        this.nodes = nodes;
        this.partitioningStrategy = partitioningStrategy;
        this.distributionStrategy = distributionStrategy;
        this.transformationStrategy = transformationStrategy;
        this.trackMemoryConsumption = trackMemoryConsumption;
    }
    
    /**
     * Performs data anonymization.
     *
     * @param data The data
     * @param config The privacy config
     * @return ARXResult
     * @throws IOException
     * @throws RollbackRequiredException
     * @throws ExecutionException
     * @throws InterruptedException 
     */
    public ARXDistributedResult anonymize(Data data, 
                                          ARXConfiguration config, long delay) throws IOException, RollbackRequiredException, InterruptedException, ExecutionException {
        long timeComplete = System.currentTimeMillis();

        Set<Integer> subset = getSubset(config);

        // Track memory consumption
        MemoryTracker memoryTracker = null;
        if (trackMemoryConsumption) {
            memoryTracker = new MemoryTracker(delay);
        }

        // Store definition
        DataDefinition definition = data.getDefinition().clone();
        
        // Sanity check
        int numRows = data.getHandle().getNumRows();
        if (numRows < 2) {
            throw new IllegalArgumentException("Dataset must contain at least two rows");
        }
        if (numRows < nodes) {
            throw new IllegalArgumentException("Dataset must contain at least as many records as nodes");
        }
        
        // #########################################
        // STEP 1: PARTITIONING
        // #########################################
        
        long timePrepare = System.currentTimeMillis();
        List<ARXPartition> partitions;
        switch (partitioningStrategy) {
        case RANDOM:
            // Config:
            partitions = ARXPartition.getPartitionsRandom(data, subset, this.nodes);
            break;
        case SORTED:
            partitions = ARXPartition.getPartitionsSorted(data, subset, this.nodes);
            break;
        default:
            throw new RuntimeException("No partition strategy specified!");
        }
        timePrepare = System.currentTimeMillis() - timePrepare;
        
        // #########################################
        // STEP 2: ANONYMIZATION
        // #########################################

        // Start time measurement
        long timeAnon = System.currentTimeMillis();
        long timeGlobalTransform = 0L;

        // ##########################################
        // STEP 2a: IF GLOBAL, RETRIEVE COMMON SCHEME
        // ##########################################
        // Global transformation
        int[] transformation = null;
        if (!config.isPrivacyModelSpecified(EDDifferentialPrivacy.class) &&
            transformationStrategy != TransformationStrategy.LOCAL) {
            List<int[]> transformations = getTransformations(partitions,
                    config,
                    distributionStrategy);
            timeAnon = System.currentTimeMillis() - timeAnon;
            timeGlobalTransform = System.currentTimeMillis();
            transformation = getCommonScheme(transformations, transformationStrategy);
        }
        
        // ###############################################
        // STEP 2b: PERFORM LOCAL OR GLOBAL TRANSFORMATION
        // ###############################################

        // Anonymize
        List<Future<DataHandle>> futures = getAnonymization(partitions, 
                                                            config, 
                                                            distributionStrategy, 
                                                            transformation);
        
        // Wait for execution
        // Note: ARX guarantees that the order of records does not change during anonymization
        List<DataHandle> handles = getResults(futures);
        if (timeGlobalTransform == 0L) {
            timeAnon = System.currentTimeMillis() - timeAnon;
        } else {
            timeGlobalTransform = System.currentTimeMillis() - timeGlobalTransform;
        }


        // ###############################################
        // STEP 3: HANDLE NON-MONOTONIC SETTINGS
        // ###############################################
        long timeSuppress;
        long timePartitionByClass = System.currentTimeMillis();

        if (!config.isPrivacyModelSpecified(EDDifferentialPrivacy.class) &&
             config.getMonotonicityOfPrivacy() != Monotonicity.FULL) {

            // Order of the indices might change due to partitioning (e.g. during sorting)
            Set<Integer> mergedSubset = new HashSet<>();
            int addToValue = 0;
            for (int i = 0; i<handles.size(); i++) {
                for (int value : partitions.get(i).getSubset()) {
                    mergedSubset.add(value + addToValue);
                }
                addToValue = addToValue + partitions.get(i).getData().getNumRows();
            }
            // Prepare merged dataset
            ARXDistributedResult mergedResult = new ARXDistributedResult(ARXPartition.getData(handles));

            Data merged = ARXPartition.getData(mergedResult.getOutput());
            merged.getDefinition().read(definition);
            
            // Partition sorted while keeping sure to assign records 
            // within equivalence classes to exactly one partition
            // Also removes all hierarchies
            partitions = ARXPartition.getPartitionsByClass(merged, mergedSubset, nodes);

            timePartitionByClass = System.currentTimeMillis() - timePartitionByClass;
            timeSuppress = System.currentTimeMillis();

            // Fix transformation scheme: all zero
            config = config.clone();
            config.setSuppressionLimit(1d);
            transformation = new int[definition.getQuasiIdentifyingAttributes().size()];
            
            // Suppress equivalence classes
            futures = getAnonymization(partitions, config, distributionStrategy, transformation);
            
            // Wait for execution
            handles = getResults(futures);
            timeSuppress = System.currentTimeMillis() - timeSuppress;

        } else {
            timePartitionByClass = 0L;
            timeSuppress = 0L;
        }
        // ###############################################
        // STEP 4: FINALIZE
        // ###############################################
        System.out.println("Finalizing anonymization!");
        timeComplete = System.currentTimeMillis() - timeComplete;


        long timeQuality = System.currentTimeMillis();

        // Preparation for calculating loss:
        QualityConfiguration configuration = new QualityConfiguration();
        int[] indices = getIndicesOfQuasiIdentifiers(data.getHandle().getDefinition().getQuasiIdentifyingAttributes(), data.getHandle());
        String[][][] hierarchies = getHierarchies(data.getHandle(), indices, configuration);
        QualityDomainShare[] shares = getDomainShares(data.getHandle(), indices, hierarchies, configuration);
        Map<String, List<Double>> qualityMetrics = new HashMap<>();

        List<Double> granularityLosses = GranularityCalculation.calculateGranularityLosses(handles, configuration, indices, hierarchies, shares);
        for (int i = 0; i < handles.size(); i++) {
            double granularityLoss = granularityLosses.get(i);

            // Following if-statement avoids a bug in calculation of granularity when each QI is fully suppressed
            if (Double.isNaN(granularityLoss)) {
                storeQuality(qualityMetrics, "Granularity", 0.0d);
            } else {
                storeQuality(qualityMetrics, "Granularity", granularityLoss);
            }
            storeQuality(qualityMetrics, "NumRows", handles.get(i).getNumRows());
        }
        timeQuality = System.currentTimeMillis() - timeQuality;

        // Merge
        long timePostprocess = System.currentTimeMillis();
        Data result = ARXPartition.getData(handles);
        timePostprocess =  System.currentTimeMillis() - timePostprocess;
        
        // Track memory consumption
        long maxMemory = Long.MIN_VALUE;
        long numberOfMemoryMeasurements = 0;
        if (trackMemoryConsumption) {
            maxMemory = memoryTracker.getMaxBytesUsed();
            numberOfMemoryMeasurements = memoryTracker.getNumberOfMemoryMeasurements();
        }

        partitions.clear();

        // Done
        return new ARXDistributedResult(result, timePrepare, timeComplete, timeAnon, timeGlobalTransform, timePartitionByClass, timeSuppress, timeQuality, timePostprocess, qualityMetrics, maxMemory, numberOfMemoryMeasurements);
    }

    /**
     * Returns the subset of the privacy models in config.
     * Note: ARX guarantees that all privacy criteria have the same subset.
     * @param config
     * @return
     */
    private static Set<Integer> getSubset(ARXConfiguration config) {
        Set<PrivacyCriterion> privacyModels = config.getPrivacyModels();
        for (PrivacyCriterion privacyCriterion : privacyModels) {
            if (privacyCriterion.isSubsetAvailable()) {
                return convertArrayToSet(privacyCriterion.getDataSubset().getArray());
            }
        }
        return null;
    }

    /**
     * Aonymizes the partitions
     * @param partitions
     * @param config
     * @param distributionStrategy2
     * @param transformation
     * @return
     * @throws RollbackRequiredException 
     * @throws IOException 
     */
    List<Future<DataHandle>> getAnonymization(List<ARXPartition> partitions,
                                                      ARXConfiguration config,
                                                      DistributionStrategy distributionStrategy2,
                                                      int[] transformation) throws IOException, RollbackRequiredException {
        List<Future<DataHandle>> futures = new ArrayList<>();
        for (ARXPartition partition : partitions) {
            ARXConfiguration partitionConfig = config.clone();
            if (partition.getSubset() != null) {
                for (PrivacyCriterion privacyCriterion : partitionConfig.getPrivacyModels()) {
                    if (privacyCriterion instanceof ModelWithExchangeableSubset) {
                        partitionConfig.removeCriterion(privacyCriterion);
                        partitionConfig.addPrivacyModel(((ModelWithExchangeableSubset) privacyCriterion).cloneAndExchangeDataSubset(DataSubset.create(partition.getData().getNumRows(), partition.getSubset())));
                    }
                }
            }
            switch (distributionStrategy) {
            case LOCAL:
                if (transformation != null) {
                    
                    // Get handle
                    Set<String> quasiIdentifiers = partition.getData().getDefinition().getQuasiIdentifyingAttributes();
                    
                    // Fix transformation levels
                    int count = 0;
                    for (int column = 0; column < partition.getData().getNumColumns(); column++) {
                        String attribute = partition.getData().getAttributeName(column);
                        if (quasiIdentifiers.contains(attribute)) {
                            int level = transformation[count];
                            partition.getData().getDefinition().setMinimumGeneralization(attribute, level);
                            partition.getData().getDefinition().setMaximumGeneralization(attribute, level);
                            count++;
                        }
                    }

                    futures.add(new ARXWorkerLocal().anonymize(partition, partitionConfig));
                } else {
                    futures.add(new ARXWorkerLocal().anonymize(partition, partitionConfig, O_MIN));
                }
                break;
            default:
                throw new IllegalStateException("Unknown distribution strategy");
            }
        }
        // Done
        return futures;
    }

    /**
     * Converts an int[] array to a set.
     * @param array
     * @return
     */
    public static Set<Integer> convertArrayToSet(int[] array) {
        Set<Integer> resultSet = new HashSet<>();
        for (int value : array) {
            resultSet.add(value);
        }
        return resultSet;
    }

    /**
     * Collects results from the futures
     * @param <T>
     * @param futures
     * @return
     * @throws ExecutionException 
     * @throws InterruptedException 
     */
    private <T> List<T> getResults(List<Future<T>> futures) throws InterruptedException, ExecutionException {
        ArrayList<T> results = new ArrayList<>();
        while (!futures.isEmpty()) {
            Iterator<Future<T>> iter = futures.iterator();
            while (iter.hasNext()) {
                Future<T> future = iter.next();
                if (future.isDone()) {
                    results.add(future.get());
                    iter.remove();
                }
            }
            Thread.sleep(WAIT_TIME);
        }
        return results;
    }
    
    /**
     * Retrieves the transformations for all partitions
     *
     * @param partitions
     * @param config
     * @param distributionStrategy
     * @return
     * @throws IOException
     * @throws InterruptedException
     * @throws ExecutionException
     */
    private List<int[]> getTransformations(List<ARXPartition> partitions, ARXConfiguration config, DistributionStrategy distributionStrategy) throws IOException, InterruptedException, ExecutionException {
        
        // Calculate schemes
        List<Future<int[]>> futures = new ArrayList<>();
        for (ARXPartition partition : partitions) {
            switch (distributionStrategy) {
            case LOCAL:
                futures.add(new ARXWorkerLocal().transform(partition, config));
                break;
            default:
                throw new IllegalStateException("Unknown distribution strategy");
            }
        }

        // Collect schemes
        return getResults(futures);
    }

    /**
     * Retrieves the common transformation scheme using all the schemes
     * @param schemes
     * @param transformationStrategy
     */
    private int[] getCommonScheme(List<int[]> schemes, TransformationStrategy transformationStrategy) {
        // Apply strategy
        switch (transformationStrategy) {
            case GLOBAL_AVERAGE:
                // Sum up all levels
                int[] result = new int[schemes.get(0).length];
                for (int[] scheme : schemes) {
                    for (int i=0; i < result.length; i++) {
                        result[i] += scheme[i];
                    }
                }
                // Divide by number of levels
                for (int i=0; i < result.length; i++) {
                    result[i] = (int)Math.round((double)result[i] / (double)schemes.size());
                }
                return result;
            case GLOBAL_MINIMUM:
                // Find minimum levels
                result = new int[schemes.get(0).length];
                Arrays.fill(result, Integer.MAX_VALUE);
                for (int[] scheme : schemes) {
                    for (int i=0; i < result.length; i++) {
                        result[i] = Math.min(result[i], scheme[i]);
                    }
                }
                return result;
            case LOCAL:
                throw new IllegalStateException("Must not be executed when doing global transformation");
            default:
                throw new IllegalStateException("Unknown transformation strategy");
        }
    }

    /**
     * Store metrics
     * @param map
     * @param label
     * @param value
     */
    private void storeQuality(Map<String, List<Double>> map, String label, double value) {
        if (!map.containsKey(label)) {
            map.put(label, new ArrayList<Double>());
        }
        map.get(label).add(value);
    }
}
