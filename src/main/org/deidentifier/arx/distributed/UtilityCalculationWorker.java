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

import org.deidentifier.arx.DataHandle;
import org.deidentifier.arx.aggregates.quality.QualityConfiguration;
import org.deidentifier.arx.aggregates.quality.QualityDomainShare;

import java.util.concurrent.Callable;

import static org.deidentifier.arx.distributed.GranularityCalculation.calculateLossDirectly;

/**
 *  Worker class to calculate utility.
 */
public class UtilityCalculationWorker implements Callable<Double> {

    private final DataHandle handle;
    private final QualityConfiguration configuration;
    private final int[] indices;
    private final String[][][] hierarchies;
    private final QualityDomainShare[] shares;

    public UtilityCalculationWorker(DataHandle handle,
                                 QualityConfiguration configuration,
                                 int[] indices,
                                 String[][][] hierarchies,
                                 QualityDomainShare[] shares) {
        this.handle = handle;
        this.configuration = configuration;
        this.indices = indices;
        this.hierarchies = hierarchies;
        this.shares = shares;
    }

    @Override
    public Double call() {
        return calculateLossDirectly(handle, configuration, indices, hierarchies, shares);
    }
}