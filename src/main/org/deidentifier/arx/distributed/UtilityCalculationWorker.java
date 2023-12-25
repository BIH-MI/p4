package org.deidentifier.arx.distributed;

import org.deidentifier.arx.DataHandle;
import org.deidentifier.arx.aggregates.quality.QualityConfiguration;
import org.deidentifier.arx.aggregates.quality.QualityDomainShare;

import java.util.concurrent.Callable;

import static org.deidentifier.arx.distributed.GranularityCalculation.calculateLossDirectly;

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
    public Double call() throws Exception {
        return calculateLossDirectly(handle, configuration, indices, hierarchies, shares);
    }
}