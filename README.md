Parallel Privacy Preservation through Partitioning (P4) 
====

Introduction
------

This repository contains the code and data used to generate the results described in our paper.

P4 is a method for distributed anonymization with the goal of reducing runtime. This is a prototype implementation
based on ARX (https://github.com/arx-deidentifier/arx)

Data
------
We use two datasets:
**US Census:**
As described in Prasser et al.[1], with eight categorical and one numerical
attribute. The dataset is an excerpt of the 1994 US Census dataset. Further information can be found at http://archive.ics.uci.edu/ml/datasets/adult.
where records containing "null" values have been removed. 

**Health interviews:**
As described in Prasser et al.[1], with 5 categorical and four numeric attributes.
The dataset comes from the US Integrated Health Interview Series. Further information can be found at https://nhis.ipums.org/nhis/.

**Varied US Census datasets:** 
In the paper we described how we created variations of the US Census dataset. 
The code can be found in the GenerateTestData class.

All the data and the generalization hierarchies that we used can be found in the data folder.

Results
------
The plotting was done using the iPythonNotebooks in the folder evaluation/plots. 

Development setup
------

### Generating the Jar File

The jar file required to run the experiments can be generated by executing the Ant target `jars`. The generated jar will be located in `jars/distributed.jar`.

### Running the Experiments

When executing the jar file, ensure the `data` folder is in the same directory as the jar file.

To start an experiment, four parameters must be specified in the following order:
- `<measureMemory?>`: Indicates whether memory usage should be measured. If false time is measured.
- `<testScalability?>`: Indicates whether scalability experiment should be performed
- `<datasetName>`: The name of the dataset to be used.
- `<sensitiveAttribute>`: The sensitive attribute to be considered in the experiment.

#### Recreating the Experiments from the Paper

##### US Census Experiments

```sh
java -jar distributed.jar true false adult education
java -jar distributed.jar false false adult education
```

##### Health survey experiments

```sh
java -jar distributed.jar true false ihis EDUC
java -jar distributed.jar false false ihis EDUC
```

##### Scalability experiments
A fifth parameter will lead to the generation of the scalability datasets before conducting the experiment. 
```sh
java -jar distributed.jar false true adult education true
```

#### Generating the plots
Use the resulting files from the experiments and the python notebooks in evaluation/plots to generate the plots. 
More instructions can be found at the top of the notebooks. 

References
------
[1] Prasser F, Eicher J, Spengler H, Bild R, Kuhn KA (2020) Flexible data anonymization using ARX—Current status and 
challenges ahead. Softw: Pract Ex per 50:1277–1304

License
------
ARX (C) 2012 - 2023 Fabian Prasser and Contributors.

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.

