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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.deidentifier.arx.*;
import org.deidentifier.arx.AttributeType.Hierarchy;
import org.deidentifier.arx.aggregates.StatisticsFrequencyDistribution;
import org.deidentifier.arx.criteria.*;
import org.deidentifier.arx.distributed.ARXDistributedAnonymizer.DistributionStrategy;
import org.deidentifier.arx.distributed.ARXDistributedAnonymizer.PartitioningStrategy;
import org.deidentifier.arx.distributed.ARXDistributedAnonymizer.TransformationStrategy;
import org.deidentifier.arx.exceptions.RollbackRequiredException;
import org.deidentifier.arx.io.CSVHierarchyInput;
import org.deidentifier.arx.metric.Metric;

import static org.deidentifier.arx.distributed.GranularityCalculation.getWeightedAverageForGranularities;

/**
 * Example
 *
 * @author Fabian Prasser
 */
public class Main {

    private static final int MAX_THREADS = 64;

    private static final boolean AGGREGATION = false;

    private static final Random random = new Random(3735928559L);

    private static abstract class BenchmarkConfiguration {
        protected final String datasetName;
        protected final String sensitiveAttribute;
        private Data data;

        private BenchmarkConfiguration(String datasetName, String sensitiveAttribute) {
            this.datasetName = datasetName;
            this.sensitiveAttribute = sensitiveAttribute;
        }

        public Data loadDataset(int numVariations) throws IOException {
            String dataset = datasetName;
            if (numVariations > 0) {
                dataset = datasetName + "-enlarged-" + numVariations;
                File file = new File("data/" + dataset + ".csv");
                if (file.exists()) {
                    System.out.println("Using dataset " + dataset);
                } else {
                    String pathToDataset = "data/" + datasetName + ".csv";
                    GenerateTestData.createVariations(pathToDataset, pathToDataset.replace(".csv", "-enlarged-" + numVariations + ".csv"), numVariations);
                    System.out.println("Generated dataset " + dataset);
                }
            }
            Data data = createData(datasetName, dataset);
            if (sensitiveAttribute != null) {
                data.getDefinition().setAttributeType(sensitiveAttribute, AttributeType.SENSITIVE_ATTRIBUTE);
            }
            this.data = data;
            return data;
        };

        public Data getDataset() {
            if (this.data == null) {
                throw new RuntimeException("Dataset not created yet");
            }
            return this.data;
        }

        public abstract ARXConfiguration getConfig(boolean local, int threads);
        public abstract String getName();
        public String getDataName() {
            return datasetName;
        };
    }


    /**
     * Loads a dataset from disk
     * @param dataset
     * @return
     * @throws IOException
     */
    public static Data createData(final String dataset) throws IOException {
        return createData(dataset, dataset);
    }


        /**
         * Loads a dataset from disk
         * @param dataset
         * @return
         * @throws IOException
         */
    public static Data createData(final String dataset, final String datasetPath) throws IOException {

        Data data = Data.create("data/" + datasetPath + ".csv", StandardCharsets.UTF_8, ';');

        // Read generalization hierarchies
        FilenameFilter hierarchyFilter = new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.matches(dataset + "_hierarchy_(.)+.csv");
            }
        };

        // Create definition
        File testDir = new File("data/");
        File[] genHierFiles = testDir.listFiles(hierarchyFilter);
        Pattern pattern = Pattern.compile("_hierarchy_(.*?).csv");
        for (File file : genHierFiles) {
            Matcher matcher = pattern.matcher(file.getName());
            if (matcher.find()) {
                CSVHierarchyInput hier = new CSVHierarchyInput(file, StandardCharsets.UTF_8, ';');
                String attributeName = matcher.group(1);
                data.getDefinition().setAttributeType(attributeName, Hierarchy.create(hier.getHierarchy()));
                if (AGGREGATION) {
                    data.getDefinition().setMicroAggregationFunction(attributeName, AttributeType.MicroAggregationFunction.createSet(), true);
                }
            }
        }
        return data;
    }

    /**
     * Entry point.
     *
     * @param args the arguments
     * @throws IOException
     * @throws RollbackRequiredException 
     * @throws ExecutionException 
     * @throws InterruptedException 
     */
    public static void main(String[] args) throws IOException, RollbackRequiredException, InterruptedException, ExecutionException {
        System.out.println("Running multiple benchmarks overwrites results from previous runs!");
        System.out.println("You can pass following four required arguments: measureMemory?, testScalability?, datasetName, sensitiveAttribute");
        ARXPartition.makeDeterministic(93981238859L);
        if (args.length >= 4) {
            if (args.length > 4 && args[4].equalsIgnoreCase("true")) {
                int[] variationsCounts = {
                        5000000, 7500000, 10000000, 12500000, 15000000, 17500000, 20000000, 22500000, 25000000, // 2.5 million steps until 25 million
                        35000000,
                        45000000,
                        55000000,
                        65000000,
                        75000000,
                        85000000
                };

                GenerateTestData.createExperimentData("data/adult.csv", variationsCounts);
            }

            benchmark(Boolean.parseBoolean(args[0]), Boolean.parseBoolean(args[1]), args[2], args[3]);



        } else {
            System.out.println("Using default: false, false, adult, education");
            benchmark(false, false, "adult", "education");
        }

    }
    
    /**
     * Benchmarking
     * @param measureMemory
     * @throws IOException
     * @throws RollbackRequiredException
     * @throws InterruptedException
     * @throws ExecutionException
     */
    private static void benchmark(boolean measureMemory, boolean testScalability, String datasetName, String sensitiveAttribute) throws IOException, RollbackRequiredException, InterruptedException, ExecutionException {

        String resultFileName = "result.csv";
        if (measureMemory) {
            resultFileName = "result_memory.csv";
        }
        // Prepare output file
        BufferedWriter out = new BufferedWriter(new FileWriter(resultFileName));
        if (measureMemory) {
            out.write("Dataset;Config;Local;Sorted;Threads;Granularity;Memory;Measurements\n");
        } else {
            out.write("Dataset;Config;Local;Sorted;Threads;Granularity;Time;TimePrepare;TimeComplete;TimeAnonymize;TimeGlobalTransform;TimePartitionByClass;TimeSuppress;TimeQuality;TimePostprocess\n");
        }

        out.flush();

        //List<BenchmarkConfiguration> configs = getConfigs(datasetName, sensitiveAttribute);
        //configs.addAll(getNewConfigs(datasetName, sensitiveAttribute));
        List<BenchmarkConfiguration> configs = getNewConfigs(datasetName, sensitiveAttribute);
        //List<BenchmarkConfiguration> configs = getConfigs(datasetName, sensitiveAttribute);


        if (testScalability) {
            configs = getScalabilityConfigs(datasetName, sensitiveAttribute);
        }


        // Configs
        System.out.println("Using " + configs.size() + " benchmark configs.");
        int benchmark_count = 0;
        for (BenchmarkConfiguration benchmark : configs) {
            benchmark_count++;
            if (testScalability) {
                System.out.println("Benchmark " + benchmark_count + "/" + configs.size());
                int[] threadCounts = {1,2,12,64};

                int[] variationsCounts = {
                        5000000, 7500000, 10000000, 12500000, 15000000, 17500000, 20000000, 22500000, 25000000, // 2.5 million steps until 25 million
                        35000000,
                        45000000,
                        55000000,
                        65000000,
                        75000000,
                        85000000
                };
                for (int threads : threadCounts) {
                    for (int numVariations : variationsCounts) {
                        System.out.println("Running with " + threads + " threads.");
                        run(benchmark, threads, false, true, out, measureMemory, numVariations, true);
                        run(benchmark, threads, true, true, out, measureMemory, numVariations, true);
                    }
                }
            } else {
                System.out.println("Benchmark " + benchmark_count + "/" + configs.size());
                for (int threads = 1; threads <= MAX_THREADS; threads++) {
                    System.out.println("Running with " + threads + " threads.");
                    run(benchmark, threads, false, true, out, measureMemory, 0, false);
                    run(benchmark, threads, true, true, out, measureMemory, 0, false);
                }
            }
        }
        
        // Done
        out.close();
    }

    /**
     * Run benchmark
     * @param threads 
     * @param local
     * @param sorted
     * @param out
     * @param measureMemory
     * @throws ExecutionException 
     * @throws InterruptedException 
     * @throws RollbackRequiredException 
     * @throws IOException 
     */
    private static void run(BenchmarkConfiguration benchmark,
                            int threads,
                            boolean local,
                            boolean sorted,
                            BufferedWriter out,
                            boolean measureMemory,
                            int numVariations,
                            boolean scalability) throws IOException,
                                                RollbackRequiredException,
                                                InterruptedException,
                                                ExecutionException {
        
        System.out.println("Config: " + benchmark.getDataName() + "." + benchmark.getName() + " local: " + local + (!measureMemory ? "" : " [MEMORY]"));
        
        double time = 0d;
        double timePrepare = 0d;
        double timeComplete = 0d;
        double timeAnonymize = 0d;
        double timeGlobalTransform = 0d;
        double timePartitionByClass = 0d;
        double timeSuppress = 0d;
        double timeQuality = 0d;
        double timePostprocess = 0d;
        double granularity = 0d;
        long memory = 0;
        long numberOfMemoryMeasurements = 0;
        long tempMemory = 0; // to store current run
        long tempNumberOfMemoryMeasurements = 0; // to store current run

        long delay = 1000L;
        int REPEAT = 5;
        int WARMUP = 1;
        if (measureMemory) {
            REPEAT = 4;
            WARMUP = 0;
        }
        if (scalability) {
            REPEAT = 3;
            WARMUP = 0;
        }

        // Repeat
        for (int i = 0; i < REPEAT; i++) {
            
            // Report
            System.out.println("- Run " + (i+1) + " of " + REPEAT);
            
            // Get
            Data data;
            data = benchmark.loadDataset(numVariations);
            System.out.println("Dataset was loaded into memory.");

            ARXConfiguration config = benchmark.getConfig(local, threads);
            
            // Anonymize
            ARXDistributedAnonymizer anonymizer = new ARXDistributedAnonymizer(threads,
                                                                               sorted ? PartitioningStrategy.SORTED : PartitioningStrategy.RANDOM, 
                                                                               DistributionStrategy.LOCAL,
                                                                               local ? TransformationStrategy.LOCAL : TransformationStrategy.GLOBAL_AVERAGE,
                                                                               measureMemory);
            ARXDistributedResult result = anonymizer.anonymize(data, config, delay);
            System.out.println("Dataset was anonymized");
            tempMemory = result.getMaxMemoryConsumption();
            tempNumberOfMemoryMeasurements = result.getNumberOfMemoryMeasurements();
            if (measureMemory && (tempNumberOfMemoryMeasurements < 20)) {
                // With default delay of 1000L we might not measure anything, because anonymization runs too quick
                // Ensure delay is small enough to get around 20 measurements during anonymization
                delay = (long) Math.floor(result.getTimeAnonymize() * 0.046);

                System.out.println("Rerunning with lower delay: " + delay);
                result = anonymizer.anonymize(data, config, delay);
                tempMemory = result.getMaxMemoryConsumption();
                tempNumberOfMemoryMeasurements = result.getNumberOfMemoryMeasurements();
                System.out.println("Memory: " + memory + " from " + tempNumberOfMemoryMeasurements + "measurements");
            }
            //result.getOutput().save("./" + benchmark.getName() + "_" + threads + "_" + local + ".csv");


            // First two are warmup
            if (i >= WARMUP) {
                memory += tempMemory;
                numberOfMemoryMeasurements += tempNumberOfMemoryMeasurements;
                granularity += getWeightedAverageForGranularities(result.getQuality().get("Granularity"), result.getQuality().get("NumRows"));
                time += result.getTimePrepare() + result.getTimeComplete() + result.getTimePostprocess() + result.getTimeQuality();
                timePrepare += result.getTimePrepare();
                timeComplete += result.getTimeComplete();
                timeAnonymize += result.getTimeAnonymize();
                timeGlobalTransform += result.getTimeGlobalTransform();
                timePartitionByClass += result.getTimePartitionByClass();
                timeSuppress += result.getTimeSuppress();
                timeQuality += result.getTimeQuality();
                timePostprocess += result.getTimePostprocess();
            }
        }

        // Average
        time /= REPEAT-WARMUP;
        timePrepare /= REPEAT-WARMUP;
        timeComplete /= REPEAT-WARMUP;
        timeAnonymize /= REPEAT-WARMUP;
        timeGlobalTransform /= REPEAT-WARMUP;
        timePartitionByClass /= REPEAT-WARMUP;
        timeSuppress /= REPEAT-WARMUP;
        timeQuality /= REPEAT-WARMUP;
        timePostprocess /= REPEAT-WARMUP;
        granularity /= REPEAT-WARMUP;
        memory /= REPEAT-WARMUP;
        numberOfMemoryMeasurements /= REPEAT-WARMUP;

        // Store
        if (numVariations > 0) {
            out.write(benchmark.getDataName() + "_" + numVariations + ";");
        } else {
            out.write(benchmark.getDataName() + ";");
        }
        out.write(benchmark.getName() + ";");
        out.write(local + ";");
        out.write(sorted + ";");
        out.write(threads + ";");
        out.write(granularity + ";");
        if (measureMemory) {
            out.write(memory + ";");
            out.write(numberOfMemoryMeasurements + ";");
            out.write("\n");
        } else {
            out.write(time + ";");
            out.write(timePrepare + ";");
            out.write(timeComplete + ";");
            out.write(timeAnonymize + ";");
            out.write(timeGlobalTransform + ";");
            out.write(timePartitionByClass + ";");
            out.write(timeSuppress + ";");
            out.write(timeQuality + ";");
            out.write(timePostprocess + "\n");
        }
        out.flush();
    }


    private static List<BenchmarkConfiguration> getScalabilityConfigs(String datasetName, String sensitiveAttribute) {
        List<BenchmarkConfiguration> configs = new ArrayList<>();
        configs.add(new BenchmarkConfiguration(datasetName, null) {
            public String getName() {return "50-anonymity";}
            public ARXConfiguration getConfig(boolean local, int threads) {
                ARXConfiguration config = ARXConfiguration.create();
                config.addPrivacyModel(new KAnonymity(50));
                config.setQualityModel(Metric.createLossMetric(local ? 0d : 0.5d));
                config.setSuppressionLimit(1d);
                return config;
            }
        });
        return configs;
    }

    /**
     * Generates a subset of m indices, from a list with indices ranging from 0 to N-1, and saves it to a file at filepath.
     * If m > N throws a runtime exception.
     * @param N N-1 is the max indices value
     * @param m number of indices in the resulting file
     * @param filePath path to the resulting file
     * @throws IOException
     */
    private static void generateSubset(int N, int m, String filePath) throws IOException {
        if (m > N) {
            throw new IllegalArgumentException("Sample size m cannot be greater than the range N");
        }

        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            indices.add(i);
        }

        Collections.shuffle(indices, random);
        List<Integer> selectedIndices = indices.subList(0, m);
        Collections.sort(selectedIndices);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            for (int index : selectedIndices) {
                writer.write(index + "\n");
            }
        }
    }

    /**
     * Loads a subset from a file containing Integers.
     * @param filePath path to the file containing Integers
     * @return Set of Integers representing the subset
     * @throws IOException
     */
    private static Set<Integer> loaSubsetFromFile(String filePath) throws IOException {
        Set<Integer> indices = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                indices.add(Integer.parseInt(line));
            }
        }
        return indices;
    }

    private static List<BenchmarkConfiguration> getNewConfigs(String datasetName, String sensitiveAttribute) {
        // Prepare configs
        List<BenchmarkConfiguration> configs = new ArrayList<>();

        configs.add(new BenchmarkConfiguration(datasetName, null) {
            public String getName() {return "5-map subset";}
            public ARXConfiguration getConfig(boolean local, int threads) {
                ARXConfiguration config = ARXConfiguration.create();
                Data dataset = getDataset();
                int n = dataset.getHandle().getNumRows();
                int m = (int) (dataset.getHandle().getNumRows() * 0.5); // Subset is 50% of dataset
                String subsetName = datasetName + "_subset_" + n + "_" + m + ".csv";
                Set<Integer> subsetIndices;
                try {
                    // Check if the file exists
                    if (!Files.exists(Paths.get(subsetName))){
                        generateSubset(n, m, subsetName);
                    }
                    subsetIndices = loaSubsetFromFile(subsetName);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to generate subset");
                }
                DataSubset subset = DataSubset.create(getDataset(), subsetIndices);

                config.addPrivacyModel(new KMap(5, subset));
                config.setQualityModel(Metric.createLossMetric(local ? 0d : 0.5d));
                config.setSuppressionLimit(1d);
                return config;
            }
        });

        configs.add(new BenchmarkConfiguration(datasetName, null) {
            public String getName() {return "5-map Estimate";}
            public ARXConfiguration getConfig(boolean local, int threads) {
                ARXConfiguration config = ARXConfiguration.create();
                DataHandle handle = getDataset().getHandle();
                ARXPopulationModel populationModel = ARXPopulationModel.create(handle.getNumRows(), 0.01d);
                config.addPrivacyModel(new KMap(5, 0.01, populationModel, KMap.CellSizeEstimator.POISSON));
                config.setQualityModel(Metric.createLossMetric(local ? 0d : 0.5d));
                config.setSuppressionLimit(1d);
                return config;
            }
        });

        configs.add(new BenchmarkConfiguration(datasetName, null) {
            public String getName() {return "profitability";}
            public ARXConfiguration getConfig(boolean local, int threads) {
                ARXConfiguration config = ARXConfiguration.create();
                config.addPrivacyModel(new ProfitabilityProsecutor());
                config.setQualityModel(Metric.createLossMetric(local ? 0d : 0.5d));
                config.setSuppressionLimit(1d);
                return config;
            }
        });
        return configs;
    }

        private static List<BenchmarkConfiguration> getConfigs(String datasetName, String sensitiveAttribute) {
        // Prepare configs
        List<BenchmarkConfiguration> configs = new ArrayList<>();

        configs.add(new BenchmarkConfiguration(datasetName, null) {
            public String getName() {return "5-anonymity";}
            public ARXConfiguration getConfig(boolean local, int threads) {
                ARXConfiguration config = ARXConfiguration.create();
                config.addPrivacyModel(new KAnonymity(5));
                config.setQualityModel(Metric.createLossMetric(local ? 0d : 0.5d));
                config.setSuppressionLimit(1d);
                return config;
            }
        });

        configs.add(new BenchmarkConfiguration(datasetName, null) {
            public String getName() {return "11-anonymity";}
            public ARXConfiguration getConfig(boolean local, int threads) {
                ARXConfiguration config = ARXConfiguration.create();
                config.addPrivacyModel(new KAnonymity(5));
                config.setQualityModel(Metric.createLossMetric(local ? 0d : 0.5d));
                config.setSuppressionLimit(1d);
                return config;
            }
        });

        configs.add(new BenchmarkConfiguration(datasetName, sensitiveAttribute) {
            public String getName() {return "distinct-3-diversity";}
            public ARXConfiguration getConfig(boolean local, int threads) {
                ARXConfiguration config = ARXConfiguration.create();
                config.addPrivacyModel(new DistinctLDiversity(sensitiveAttribute, 3));
                config.setQualityModel(Metric.createLossMetric(local ? 0d : 0.5d));
                config.setSuppressionLimit(1d);
                return config;
            }
        });

        configs.add(new BenchmarkConfiguration(datasetName, sensitiveAttribute) {
            public String getName() {return "distinct-5-diversity";}
            public ARXConfiguration getConfig(boolean local, int threads) {
                ARXConfiguration config = ARXConfiguration.create();
                config.addPrivacyModel(new DistinctLDiversity(sensitiveAttribute, 5));
                config.setQualityModel(Metric.createLossMetric(local ? 0d : 0.5d));
                config.setSuppressionLimit(1d);
                return config;
            }
        });

        configs.add(new BenchmarkConfiguration(datasetName, sensitiveAttribute) {
            public String getName() {return "entropy-3-diversity";}
            public ARXConfiguration getConfig(boolean local, int threads) {
                ARXConfiguration config = ARXConfiguration.create();
                config.addPrivacyModel(new EntropyLDiversity(this.sensitiveAttribute, 3));
                config.setQualityModel(Metric.createLossMetric(local ? 0d : 0.5d));
                config.setSuppressionLimit(1d);
                return config;
            }
        });

        //configs.add(new BenchmarkConfiguration(datasetName, sensitiveAttribute) {
        //    public String getName() {return "entropy-5-diversity";}
        //    public ARXConfiguration getConfig(boolean local, int threads) {
        //        ARXConfiguration config = ARXConfiguration.create();
        //        config.addPrivacyModel(new EntropyLDiversity(this.sensitiveAttribute, 5));
        //        config.setQualityModel(Metric.createLossMetric(local ? 0d : 0.5d));
        //        config.setSuppressionLimit(1d);
        //        return config;
        //    }
        //});

        configs.add(new BenchmarkConfiguration(datasetName, sensitiveAttribute) {
            public String getName() {return "0.2-equal-closeness (global distribution)";}
            public ARXConfiguration getConfig(boolean local, int threads) {

                // Variable
                String VARIABLE = this.sensitiveAttribute;

                // Obtain global distribution
                StatisticsFrequencyDistribution distribution;
                DataHandle handle = getDataset().getHandle();
                int column = handle.getColumnIndexOf(VARIABLE);
                distribution = handle.getStatistics().getFrequencyDistribution(column);

                ARXConfiguration config = ARXConfiguration.create();
                config.addPrivacyModel(new EqualDistanceTCloseness(VARIABLE, 0.2d, distribution));
                config.setQualityModel(Metric.createLossMetric(local ? 0d : 0.5d));
                config.setSuppressionLimit(1d);
                return config;
            }
        });

        configs.add(new BenchmarkConfiguration(datasetName, sensitiveAttribute) {
            public String getName() {return "0.5-equal-closeness (global distribution)";}
            public ARXConfiguration getConfig(boolean local, int threads) {

                // Variable
                String VARIABLE = this.sensitiveAttribute;

                // Obtain global distribution
                StatisticsFrequencyDistribution distribution;
                DataHandle handle = getDataset().getHandle();
                int column = handle.getColumnIndexOf(VARIABLE);
                distribution = handle.getStatistics().getFrequencyDistribution(column);

                ARXConfiguration config = ARXConfiguration.create();
                config.addPrivacyModel(new EqualDistanceTCloseness(VARIABLE, 0.5d, distribution));
                config.setQualityModel(Metric.createLossMetric(local ? 0d : 0.5d));
                config.setSuppressionLimit(1d);
                return config;
            }
        });

        configs.add(new BenchmarkConfiguration(datasetName, sensitiveAttribute) {
            public String getName() {return "1-disclosure-privacy";}
            public ARXConfiguration getConfig(boolean local, int threads) {
                ARXConfiguration config = ARXConfiguration.create();
                config.addPrivacyModel(new DDisclosurePrivacy(this.sensitiveAttribute, 1));
                config.setQualityModel(Metric.createLossMetric(local ? 0d : 0.5d));
                config.setSuppressionLimit(1d);
                return config;
            }
        });

        configs.add(new BenchmarkConfiguration(datasetName, sensitiveAttribute) {
            public String getName() {return "2-disclosure-privacy";}
            public ARXConfiguration getConfig(boolean local, int threads) {
                ARXConfiguration config = ARXConfiguration.create();
                config.addPrivacyModel(new DDisclosurePrivacy(this.sensitiveAttribute, 2));
                config.setQualityModel(Metric.createLossMetric(local ? 0d : 0.5d));
                config.setSuppressionLimit(1d);
                return config;
            }
        });

        configs.add(new BenchmarkConfiguration(datasetName, sensitiveAttribute) {
            public String getName() {return "1-enhanced-likeness (global distribution)";}
            public ARXConfiguration getConfig(boolean local, int threads) {

                // Variable
                String VARIABLE = this.sensitiveAttribute;

                // Obtain global distribution
                StatisticsFrequencyDistribution distribution;
                DataHandle handle = getDataset().getHandle();
                int column = handle.getColumnIndexOf(VARIABLE);
                distribution = handle.getStatistics().getFrequencyDistribution(column);

                ARXConfiguration config = ARXConfiguration.create();
                config.addPrivacyModel(new EnhancedBLikeness(VARIABLE, 1, distribution));
                config.setQualityModel(Metric.createLossMetric(local ? 0d : 0.5d));
                config.setSuppressionLimit(1d);
                return config;
            }
        });

        configs.add(new BenchmarkConfiguration(datasetName, sensitiveAttribute) {
            public String getName() {return "2-enhanced-likeness (global distribution)";}
            public ARXConfiguration getConfig(boolean local, int threads) {

                // Variable
                String VARIABLE = this.sensitiveAttribute;

                // Obtain global distribution
                StatisticsFrequencyDistribution distribution;
                DataHandle handle = getDataset().getHandle();
                int column = handle.getColumnIndexOf(VARIABLE);
                distribution = handle.getStatistics().getFrequencyDistribution(column);

                ARXConfiguration config = ARXConfiguration.create();
                config.addPrivacyModel(new EnhancedBLikeness(VARIABLE, 2, distribution));
                config.setQualityModel(Metric.createLossMetric(local ? 0d : 0.5d));
                config.setSuppressionLimit(1d);
                return config;
            }
        });

        configs.add(new BenchmarkConfiguration(datasetName, null) {
            public String getName() {return "0.05-average-risk";}
            public ARXConfiguration getConfig(boolean local, int threads) {
                ARXConfiguration config = ARXConfiguration.create();
                config.addPrivacyModel(new AverageReidentificationRisk(0.05d));
                config.setQualityModel(Metric.createLossMetric(local ? 0d : 0.5d));
                config.setSuppressionLimit(1d);
                return config;
            }
        });

        configs.add(new BenchmarkConfiguration(datasetName, null) {
            public String getName() {return "0.01-average-risk";}
            public ARXConfiguration getConfig(boolean local, int threads) {
                ARXConfiguration config = ARXConfiguration.create();
                config.addPrivacyModel(new AverageReidentificationRisk(0.01d));
                config.setQualityModel(Metric.createLossMetric(local ? 0d : 0.5d));
                config.setSuppressionLimit(1d);
                return config;
            }
        });

        configs.add(new BenchmarkConfiguration(datasetName, null) {
            public String getName() {return "0.01-sample-uniqueness";}
            public ARXConfiguration getConfig(boolean local, int threads) {
                ARXConfiguration config = ARXConfiguration.create();
                DataHandle handle = getDataset().getHandle();
                config.addPrivacyModel(new SampleUniqueness(0.01));
                config.setQualityModel(Metric.createLossMetric(local ? 0d : 0.5d));
                config.setSuppressionLimit(1d);
                return config;
            }
        });

        return configs;
    }

    private static List<BenchmarkConfiguration> getDifferentialPrivacyConfigs(String datasetName, String sensitiveAttribute) {
        List<BenchmarkConfiguration> configs = new ArrayList<>();

        configs.add(new BenchmarkConfiguration(datasetName, null) {
            final double EPSILON = 1d;
            public String getName() {return "(e10-6, "+EPSILON+")-differential privacy";}
            public ARXConfiguration getConfig(boolean local, int threads) {
                ARXConfiguration config = ARXConfiguration.create();
                config.addPrivacyModel(new EDDifferentialPrivacy(EPSILON / (double) threads, 0.000001d, null, true));
                config.setDPSearchBudget(0.1d * (EPSILON / (double) threads));
                config.setHeuristicSearchStepLimit(300);
                config.setQualityModel(Metric.createLossMetric(0.5d));
                config.setSuppressionLimit(1d);
                return config;
            }
        });

        configs.add(new BenchmarkConfiguration(datasetName, null) {
            final double EPSILON = 2d;
            public String getName() {return "(e10-6, "+EPSILON+")-differential privacy";}
            public ARXConfiguration getConfig(boolean local, int threads) {
                ARXConfiguration config = ARXConfiguration.create();
                config.addPrivacyModel(new EDDifferentialPrivacy(EPSILON / (double) threads, 0.000001d, null, true));
                config.setDPSearchBudget(0.1d * (EPSILON / (double) threads));
                config.setHeuristicSearchStepLimit(300);
                config.setQualityModel(Metric.createLossMetric(0.5d));
                config.setSuppressionLimit(1d);
                return config;
            }
        });

        configs.add(new BenchmarkConfiguration(datasetName, null) {
            final double EPSILON = 3d;
            public String getName() {return "(e10-6, "+EPSILON+")-differential privacy";}
            public ARXConfiguration getConfig(boolean local, int threads) {
                ARXConfiguration config = ARXConfiguration.create();
                config.addPrivacyModel(new EDDifferentialPrivacy(EPSILON / (double) threads, 0.000001d, null, true));
                config.setDPSearchBudget(0.1d * (EPSILON / (double) threads));
                config.setHeuristicSearchStepLimit(300);
                config.setQualityModel(Metric.createLossMetric(0.5d));
                config.setSuppressionLimit(1d);
                return config;
            }
        });

        configs.add(new BenchmarkConfiguration(datasetName, null) {
            final double EPSILON = 4d;
            public String getName() {return "(e10-6, "+EPSILON+")-differential privacy";}
            public ARXConfiguration getConfig(boolean local, int threads) {
                ARXConfiguration config = ARXConfiguration.create();
                config.addPrivacyModel(new EDDifferentialPrivacy(EPSILON / (double) threads, 0.000001d, null, true));
                config.setDPSearchBudget(0.1d * (EPSILON / (double) threads));
                config.setHeuristicSearchStepLimit(300);
                config.setQualityModel(Metric.createLossMetric(0.5d));
                config.setSuppressionLimit(1d);
                return config;
            }
        });
        return configs;
    }
}
