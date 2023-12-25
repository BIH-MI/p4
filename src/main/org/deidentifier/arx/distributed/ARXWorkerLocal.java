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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.deidentifier.arx.ARXAnonymizer;
import org.deidentifier.arx.ARXConfiguration;
import org.deidentifier.arx.ARXResult;
import org.deidentifier.arx.DataHandle;
import org.deidentifier.arx.criteria.EDDifferentialPrivacy;
import org.deidentifier.arx.exceptions.RollbackRequiredException;

import cern.colt.Arrays;

/**
 * A local worker running in a local thread
 * @author Fabian Prasser
 */
public class ARXWorkerLocal implements ARXWorker {
    
    /** Sequential execution: for debugging purposes*/
    private static final boolean DEBUG = false;
    
    @Override
    public Future<DataHandle> anonymize(ARXPartition partition,
                                        ARXConfiguration config) throws IOException,
                                                                 RollbackRequiredException {
        return anonymize(partition, config, 0d);
    }

    @Override
    public Future<DataHandle> anonymize(final ARXPartition partition,
                                        final ARXConfiguration _config,
                                        final double recordsPerIteration) throws IOException, RollbackRequiredException {
        
        // Executor service
        ExecutorService executor = Executors.newSingleThreadExecutor();
        
        // Clone
        ARXConfiguration config = _config.clone();
        
        // Execute 
        Future<DataHandle> future = executor.submit(new Callable<DataHandle>() {
            @Override
            public DataHandle call() throws Exception {
                
                // Prepare local transformation
                if (recordsPerIteration != 0d) {
                    config.setSuppressionLimit(1d - recordsPerIteration);
                }
                
                // Anonymize
                ARXAnonymizer anonymizer = new ARXAnonymizer();
                ARXResult result = anonymizer.anonymize(ARXPartition.getData(partition.getData()), config);
                DataHandle handle = result.getOutput();
                
                if (!result.isResultAvailable()) {
                    
                    config.setSuppressionLimit(1d);
                    anonymizer = new ARXAnonymizer();
                    result = anonymizer.anonymize(ARXPartition.getData(partition.getData()), config);
                    handle = result.getOutput();
                }
                
                // Local transformation
                else if (!config.isPrivacyModelSpecified(EDDifferentialPrivacy.class) && 
                          recordsPerIteration != 0d) {
                    result.optimizeIterativeFast(handle, recordsPerIteration);
                }
                
                if (DEBUG) {
                    System.out.println("Anonymization");
                    System.out.println(" - Dataset size: " + handle.getNumRows());
                    System.out.println(" - Records suppressed: " + handle.getStatistics().getEquivalenceClassStatistics().getNumberOfSuppressedRecords());
                    System.out.println(" - Transformation scheme: " + Arrays.toString(result.getGlobalOptimum().getTransformation()));
                }
                
                // Done
                executor.shutdown();
                return handle;
            }
        });
        
        // Wait
        if (DEBUG) {
            while (!future.isDone()) {
                try {
                    Thread.sleep(100);
                } catch (Exception e) {
                    // Swallow
                }
            }
        }
        
        // Done
        return future;
    }

    @Override
    public Future<int[]> transform(ARXPartition partition, ARXConfiguration _config) throws IOException {
        
        // Executor service
        ExecutorService executor = Executors.newSingleThreadExecutor();
        
        // Clone
        ARXConfiguration config = _config.clone();
        
        // Execute 
        Future<int[]> future = executor.submit(new Callable<int[]>() {
            @Override
            public int[] call() throws Exception {
                
                // Anonymize
                ARXAnonymizer anonymizer = new ARXAnonymizer();
                ARXResult result = anonymizer.anonymize(ARXPartition.getData(partition.getData()), config);
                int[] transformation = result.getGlobalOptimum().getTransformation();
                if (DEBUG) {
                    System.out.println("Anonymization");
                    System.out.println(" - Dataset size: " + result.getOutput().getNumRows());
                    System.out.println(" - Records suppressed: " + result.getOutput().getStatistics().getEquivalenceClassStatistics().getNumberOfSuppressedRecords());
                    System.out.println(" - Transformation scheme: " + Arrays.toString(result.getGlobalOptimum().getTransformation()));
                }
                
                // Done
                executor.shutdown();
                return transformation;
            }
        });

        // Wait
        if (DEBUG) {
            while (!future.isDone()) {
                try {
                    Thread.sleep(100);
                } catch (Exception e) {
                    // Swallow
                }
            }
        }
        
        // Done
        return future;
    }
}
