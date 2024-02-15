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
package org.deidentifier.arx;

import org.deidentifier.arx.framework.check.distribution.DistributionAggregateFunction;
import org.deidentifier.arx.framework.data.DataManager;
import org.deidentifier.arx.framework.data.DataMatrix;
import org.deidentifier.arx.framework.data.Dictionary;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Class to retrieve intermediate results of the original ARXAnonymizer.
 */
public class ARXAnonymizerIntermediate extends ARXAnonymizer {

    /**
     * Constructor
     */
    public ARXAnonymizerIntermediate() {
        super();
    }

    /**
     * Returns a DataManager equal to the one used by in the anonymize() method in the ARXAnonymizer.
     * @param data
     * @param config
     * @return DataManager
     * @throws IOException
     */
    public DataManager getDataManagerInstance(Data data, ARXConfiguration config) throws IOException {
        if (((DataHandleInput) data.getHandle()).isLocked()) {
            throw new RuntimeException("This data handle is locked. Please release it first");
        } else {
            DataHandle handle = data.getHandle();
            handle.getDefinition().materializeHierarchies(handle);
            handle.getRegistry().reset();
            DataManager manager = getDataManager(handle, handle.getDefinition(), config);
            handle.getRegistry().createInputSubset(config);
            ((DataHandleInput) handle).update(manager.getDataGeneralized().getArray(), manager.getDataAnalyzed().getArray());

            return getDataManager(data.getHandle(), data.getHandle().getDefinition(), config);
        }
    }

    /**
     * copied from ARXAnonymizer
     * @param handle
     * @param definition
     * @param config
     * @return
     * @throws IOException
     */
    private DataManager getDataManager(DataHandle handle, DataDefinition definition, ARXConfiguration config) throws IOException {
        String[] header = handle.header;
        DataMatrix dataArray = ((DataHandleInput)handle).data;
        Dictionary dictionary = ((DataHandleInput)handle).dictionary;
        DataManager manager = new DataManager(header, dataArray, dictionary, definition, this.getAggregateFunctions(definition), config);
        return manager;
    }

    /**
     * copied from ARXAnonymizer
     * @param definition
     * @return
     */
    private Map<String, DistributionAggregateFunction> getAggregateFunctions(DataDefinition definition) {
        Map<String, DistributionAggregateFunction> result = new HashMap();
        Iterator var3 = definition.getQuasiIdentifiersWithMicroaggregation().iterator();

        while(var3.hasNext()) {
            String key = (String)var3.next();
            result.put(key, definition.getMicroAggregationFunction(key).getFunction());
        }

        return result;
    }

}