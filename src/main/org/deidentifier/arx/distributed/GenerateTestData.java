package org.deidentifier.arx.distributed;


import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class GenerateTestData {

    // Separator used in the CSV file.
    private static final String DELIMITER = ";";

    // Ratio to decide the likelihood of varying an attribute in a record.
    private static final double PHO = 0.6666;

    // Random number generator.
    private static final Random RANDOM = new Random();

    // Stores original records from the CSV.
    private static final List<List<String>> recordList = new ArrayList<>();

    // Stores unique values of each attribute for quick lookup.
    private static final List<Set<String>> attributeValueSets = new ArrayList<>();

    // List indicating if an attribute is numeric or not.
    private static final List<Boolean> isNumericAttribute = new ArrayList<>();

    // A list that stores the minimum value for each numeric attribute.
    private static final List<Double> numericAttributeMin = new ArrayList<>();

    // A list that stores the maximum value for each numeric attribute.
    private static final List<Double> numericAttributeMax = new ArrayList<>();

    /**
     * Main method for creating experiment data. It manages the creation of datasets with various sizes.
     * The resulting files will be generated next to the reference dataset with the suffix -enlarged-{numVariations}
     *
     * @param inputPath path to the reference dataset
     * @param numVariations number of variations to create
     */
    public static void createExperimentData(String inputPath, int[] numVariations) {
        System.out.println("Creating experiment data for " + inputPath);
        // order numVariations from small to big
        Arrays.sort(numVariations);
        // Reverse the array to get big to small
        for (int i = 0, j = numVariations.length - 1; i < j; i++, j--) {
            int temp = numVariations[i];
            numVariations[i] = numVariations[j];
            numVariations[j] = temp;
        }
        // create a dataset with the biggest numVariation
        String lastCreated = inputPath.replace(".csv", "-enlarged-" + numVariations[0] + ".csv");
        createVariations(inputPath, lastCreated,  numVariations[0]);
        System.out.println("Created " + lastCreated);
        for (int i = 1; i < numVariations.length; i++) {
            // sample the next biggest numVariation from the previous generated datset until done
            lastCreated = sampleFromDataset(lastCreated, inputPath.replace(".csv", "-enlarged-" + numVariations[i] + ".csv"), numVariations[i]);
            System.out.println("Created " + lastCreated);
        }
    }

    /**
     * Samples a given number of variations from a dataset.
     *
     * @param datasetPath The file path of the dataset.
     * @param sampleSize  The number of variations to sample.
     * @return The path to the sampled dataset.
     */
    public static String sampleFromDataset(String datasetPath, String resultPath, int sampleSize) {
        List<String> lines;
        try {
            // Read all lines from the dataset file
            lines = Files.readAllLines(Paths.get(datasetPath));
            // Get header
            String header = lines.remove(0);
            // Shuffle the lines to randomize them
            Collections.shuffle(lines);
            List<String> sampled = lines.stream().limit(sampleSize).collect(Collectors.toList());
            // Re-insert header
            sampled.add(0, header);
            Files.write(Paths.get(resultPath), sampled);
            return resultPath;
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("Sampling from " + datasetPath + " failed.");
        }
    }

    /**
     * Initializes the data structures.
     * @param numOfAttributes Number of attributes in a record.
     */
    private static void initializeStructures(int numOfAttributes) {
        for (int i = 0; i < numOfAttributes; i++) {
            recordList.add(new ArrayList<>());
            attributeValueSets.add(new HashSet<>());
        }
    }

    /**
     * Populates data structures with the values from a given record.
     * @param record A single record (or line) from the CSV.
     */
    private static void populateStructures(String[] record) {
        for (int i = 0; i < record.length; i++) {
            // Check for the first record if the attribute is numeric
            if (i >= isNumericAttribute.size()) {
                isNumericAttribute.add(shouldTreatAsNumeric(record[i]));
            }
            if (isNumericAttribute.get(i)) {
                // For numeric attributes, add to the list
                double numericValue = Double.parseDouble(record[i]);
                // Update min and max values
                if (numericAttributeMin.size() <= i) {
                    numericAttributeMin.add(numericValue);
                    numericAttributeMax.add(numericValue);
                } else {
                    numericAttributeMin.set(i, Math.min(numericValue, numericAttributeMin.get(i)));
                    numericAttributeMax.set(i, Math.max(numericValue, numericAttributeMax.get(i)));
                }
            } else {
                if (attributeValueSets.get(i).add(record[i])) {
                    recordList.get(i).add(record[i]);
                }
            }
        }
    }

    /**
     * Generates a varied version of a given record.
     * @param record Original record.
     * @return Varied record.
     */
    private static String[] calculateVariation(String[] record) {
        String[] variation = Arrays.copyOf(record, record.length);
        for (int i = 0; i < variation.length; i++) {
            if (RANDOM.nextDouble() <= PHO) {
                if (isNumericAttribute.get(i)) {
                    double min = numericAttributeMin.get(i);
                    double max = numericAttributeMax.get(i);
                    double generatedValue = min + (max - min) * RANDOM.nextDouble();
                    variation[i] = String.valueOf(Math.round(generatedValue));
                } else {
                    int randomIndex = RANDOM.nextInt(recordList.get(i).size());
                    variation[i] = recordList.get(i).get(randomIndex);
                }
            }
        }
        return variation;
    }

    /**
     * Creates the variations of the records from the original CSV and writes them to a new CSV file.
     * @param filename Path to the original CSV.
     * @param numVariations Number of variations to generate.
     * @return The path to the varied dataset.
     */
    public static void createVariations(String filename, String targetFile, int numVariations) {
        List<String[]> records = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line;
            boolean isHeader = true;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(DELIMITER);
                records.add(fields);
                if (isHeader) {
                    initializeStructures(fields.length);
                    isHeader = false;
                } else {
                    populateStructures(fields);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(targetFile))) {
            writer.write(String.join(DELIMITER, records.get(0)) + "\n");
            // Add varied records
            for (int i = 0; i < numVariations; i++) {
                int randomRecordIndex = 1 + RANDOM.nextInt(records.size() - 1);
                String[] randomRecord = records.get(randomRecordIndex);
                String[] variedRecord = calculateVariation(randomRecord);
                writer.write(String.join(DELIMITER, variedRecord) + "\n");
                if (i % 100000 == 0) {
                    System.out.println("Processed " + i + " variations.");
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Checks if the given string represents a numeric value or not.
     *
     * @param str The string to be checked.
     * @return False if string not numeric
     */
    private static boolean shouldTreatAsNumeric(String str) {
        try {
            Double.parseDouble(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
