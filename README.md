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
TODO: Upload excel and plots to repository.
The plotting was done using the iPythonNotebooks in the folder /plots. 

Development setup
------

TODO: Explain how to build jar file. 

References
------
[1] Prasser F, Eicher J, Spengler H, Bild R, Kuhn KA (2020) Flexible data anonymization using ARX—Current status and 
challenges ahead. Softw: Pract Ex per 50:1277–1304

License
------

TODO: ? 

