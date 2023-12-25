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

import java.util.*;

import cern.colt.Swapper;
import org.deidentifier.arx.Data;
import org.deidentifier.arx.DataDefinition;
import org.deidentifier.arx.DataHandle;


/**
 * Class providing operations for partitions
 * @author Fabian Prasser
 *
 */
public class ARXPartition {

    /** Data*/
    private final DataHandle data;
    /** Subset*/
    private final Set<Integer> subset;

    /**
     * Creates a new instance
     * @param handle
     * @param subset
     */
    public ARXPartition(DataHandle handle, Set<Integer> subset) {
        this.data = handle;
        this.subset = subset;
    }

    /**
     * Returns the data
     * @return
     */
    public DataHandle getData() {
        return data;
    }

    /**
     * Returns the subset, if any
     * @return
     */
    public Set<Integer> getSubset() {
        return subset;
    }

    /** Random */
    private static Random random = new Random();

    public static void makeDeterministic(long seed) {
        random = new Random(seed);
    }


    /**
     * Converts handle to data
     * @param handle
     * @return
     */
    public static Data getData(DataHandle handle) {
        // TODO: Ugly that this is needed, because it is costly
        Data data = Data.create(handle.iterator());
        data.getDefinition().read(handle.getDefinition());
        return data;
    }
    
    /**
     * Merges several handles
     * @param handles
     * @return
     */
    public static Data getData(List<DataHandle> handles) {
        // TODO: Ugly that this is needed, because it is costly
        List<Iterator<String[]>> iterators = new ArrayList<>();
        for (DataHandle handle : handles) {
            Iterator<String[]> iterator = handle.iterator();
            if (!iterators.isEmpty()) {
                // Skip header
                iterator.next();
            }
            iterators.add(iterator);
        }
        return Data.create(new CombinedIterator<String[]>(iterators));
    }

    /**
     * Partitions the dataset making sure that records from one equivalence
     * class are assigned to exactly one partition. Will also remove all hierarchies.
     *
     * @param data           The dataset to be partitioned. (Expected to have a header)
     * @param subset         A set of indices specifying a subset of the data for partitioning.
     * @param number         The number of partitions to create.
     * @return               A list of {@link ARXPartition} objects representing the divided partitions.
     */
    public static List<ARXPartition> getPartitionsByClass(Data data, Set<Integer> subset, int number) {

        DataHandle handle = data.getHandle();

        // Prepare
        List<ARXPartition> result = new ArrayList<>();
        DataDefinition definition = data.getDefinition().clone();
        Set<String> qi = handle.getDefinition().getQuasiIdentifyingAttributes();
        for (String attribute : qi) {
            definition.resetHierarchy(attribute);
        }

        int[] qiIndices = createIndexArray(handle, qi);

        // Sort
        sortData(handle, subset, qiIndices);

        // Split
        Iterator<String[]> iter = handle.iterator();
        String[] header = iter.next();
        String[] current = iter.next();
        int currentRow = 0;
        String[] next = iter.next();
        int size = (int)Math.floor((double)handle.getNumRows() / (double)number);
        for (int i = 0; i < number && current != null; i++) {

            // Build this partition
            List<String[]> partition = new ArrayList<>();
            Set<Integer> partitionSubset = new HashSet<>();

            partition.add(header);

            // Loop while too small or in same equivalence class
            while (current != null && (partition.size() < size + 1 || equals(current, next, qiIndices))) {

                // Add
                partition.add(current);
                if (subset.contains(currentRow)) {
                    partitionSubset.add(currentRow);
                }

                // Proceed
                current = next;
                currentRow ++;
                next = iter.hasNext() ? iter.next() : null;
            }

            // Add to partitions
            Data partitionData = Data.create(partition);
            partitionData.getDefinition().read(definition.clone());
            result.add(new ARXPartition(partitionData.getHandle(), partitionSubset));
        }

        // Done
        return result;
    }

    /**
     * Creates int array with indices of the quasi identifiers.
     * @param handle
     * @param qi
     * @return
     */
    private static int[] createIndexArray(DataHandle handle, Set<String> qi) {
        // Collect indices to use for sorting
        int[] indices = new int[qi.size()];
        int num = 0;
        for (int column = 0; column < handle.getNumColumns(); column++) {
            if (qi.contains(handle.getAttributeName(column))) {
                indices[num++] = column;
            }
        }
        return indices;
    }

    /**
     * Randomly splits a dataset evenly into a specified number of partitions, ensuring that each partition
     * includes the original header. The method also considers a subset of indices to track those rows
     * across the partitions.
     *
     * @param data           The dataset to be partitioned.
     * @param subset         A  set of indices specifying a subset of the data.
     * @param number         The number of partitions to create.
     * @return               A list of {@link ARXPartition} objects representing the randomly divided partitions,
     *                       each including the original header.
     */
    public static List<ARXPartition> getPartitionsRandom(Data data, Set<Integer> subset, int number) {
        // Copy definition
        DataDefinition definition = data.getDefinition();

        // Randomly partition
        DataHandle handle = data.getHandle();
        Iterator<String[]> iter = handle.iterator();
        String[] header = iter.next();
        int currentIndex = 0;

        // Lists
        List<Set<Integer>> partitionIndices = new ArrayList<>();
        List<List<String[]>> list = new ArrayList<>();
        for (int i = 0; i < number; i++) {
            List<String[]> _list = new ArrayList<>();
            _list.add(header);
            list.add(_list);
            partitionIndices.add(new HashSet<>());
        }

        // Distributed records
        while (iter.hasNext()) {
            int randomPartition = random.nextInt(number);
            list.get(randomPartition).add(iter.next());
            if (subset.contains(currentIndex)) {
                partitionIndices.get(randomPartition).add(list.get(randomPartition).size()-2); // additional -1 for header
            }
            currentIndex++;
        }

        // Convert to data
        List<ARXPartition> result = new ArrayList<>();
        for (int i = 0; i < number; i++) {
            List<String[]> partition = list.get(i);
            Data _data = Data.create(partition);
            _data.getDefinition().read(definition.clone());
            result.add(new ARXPartition(_data.getHandle(), partitionIndices.get(i)));
        }

        // Done
        return result;
    }

    /**
     * Splits a dataset into a specified number of partitions, ensuring that each partition
     * includes the original header and is evenly distributed based on the total number of rows.
     * The dataset is sorted before the splitting.
     *
     * @param data           The dataset to be partitioned.
     * @param subset         A set of indices specifying a subset that is used during anonymization
     * @param number         The number of partitions to create.
     * @return               A list of {@link ARXPartition} objects representing the divided partitions,
     *                       each including the original header.
     */
    public static List<ARXPartition> getPartitionsSorted(Data data, Set<Integer> subset, int number) {

        // Copy definition
        DataDefinition definition = data.getDefinition();

        // Prepare
        List<ARXPartition> result = new ArrayList<>();
        DataHandle handle = data.getHandle();

        // Collect indices to use for sorting
        Set<String> qi = handle.getDefinition().getQuasiIdentifyingAttributes();
        int[] qiIndices = createIndexArray(handle, qi);

        // Sort
        sortData(handle, subset, qiIndices);

        // Convert
        Iterator<String[]> iter = handle.iterator();
        String[] header = iter.next();
        int currentIndex = 0;


        // Split
        int numRows = handle.getNumRows();
        int partitionSize = numRows / number; // Integer division
        int remainder = numRows % number; // Remainder to distribute

        for (int i = 0; i < number; i++) {
            List<String[]> partition = new ArrayList<>();
            Set<Integer> partitionSubset = new HashSet<>();
            partition.add(header);

            // Partitions not necessarily the same size. That's why the first remainder partitions get one more row each
            int currentPartitionSize = partitionSize + (i < remainder ? 1 : 0);

            for (int j = 0; j< currentPartitionSize; j++) {
                partition.add(iter.next());
                if (subset.contains(currentIndex)) {
                    partitionSubset.add(j);
                }
                currentIndex++;
            }
            Data partitionData = Data.create(partition);
            partitionData.getDefinition().read(definition.clone());
            result.add(new ARXPartition(partitionData.getHandle(), partitionSubset));
        }

        // Done
        return result;
    }

    /**
     * Sorts the data from a DataHandle based on specified quasi-identifying indices (qiIndices) and updates the subset
     * to point to same rows before and ofter sorting.
     *
     * @param handle         The DataHandle to be sorted.
     * @param subset         Set of indices specifying a subset of rows that is updated during sorting.
     * @param qiIndices      Array of indices used to determine the columns for sorting the data.
     */
    private static void sortData(DataHandle handle, Set<Integer> subset, int[] qiIndices) {
        Swapper swapper = (a, b) -> {
            boolean containsA = subset.contains(a);
            boolean containsB = subset.contains(b);

            if (containsA && !containsB) {
                // If only 'a' is in the subset, replace 'a' with 'b'
                subset.remove(a);
                subset.add(b);
            } else if (!containsA && containsB) {
                // If only 'b' is in the subset, replace 'b' with 'a'
                subset.remove(b);
                subset.add(a);
            }
            // If both are in the subset or both are not, do nothing
        };
        handle.sort(swapper, true, qiIndices);
    }

    /**
     * Checks equality of strings regarding the given indices
     * @param array1
     * @param array2
     * @param indices
     * @return
     */
    private static boolean equals(String[] array1, String[] array2, int[] indices) {
        if (array1 == null) {
            return (array2 == null);
        }
        if (array2 == null) {
            return (array1 == null);
        }
        for (int index : indices) {
            if (!array1[index].equals(array2[index])) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Print
     * @param handle
     */
    public static void print(DataHandle handle) {
        Iterator<String[]> iterator = handle.iterator();
        System.out.println(Arrays.toString(iterator.next()));
        System.out.println(Arrays.toString(iterator.next()));
        System.out.println("- Records: " + handle.getNumRows());
    }
}