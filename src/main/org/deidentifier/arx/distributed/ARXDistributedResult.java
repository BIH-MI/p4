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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.deidentifier.arx.Data;
import org.deidentifier.arx.DataHandle;

public class ARXDistributedResult {
    
    /** Quality metrics */
    private Map<String, List<Double>> qualityMetrics = new HashMap<>();
    /** Data */
    private Data                      data;
    /** Timing */
    private long                      timePrepare;

    private long                      timeComplete;
    /** Timing */
    private long                      timeAnonymize;
    /** Timing */
    private long                      timeGlobalTransform;
    /** Timing */
    private long                      timePartitionByClass;

    private long                      timeSuppress;

    /** Timing */
    private long                      timeQuality;
    /** Timing */
    private long                      timePostprocess;
    /** Max memory consumption */
    private long                      maxMemoryConsumption = Long.MIN_VALUE;

    private long                      numberOfMemoryMeasurements;

    /**
     * Creates a new instance
     * 
     * @param data
     */
    public ARXDistributedResult(Data data) {
        this(data, 0, 0, 0,  0, 0, 0, 0, 0,null, Long.MIN_VALUE, 0);
    }

    /**
     * Creates a new instance
     * @param data
     * @param timePrepare
     * @param timeAnonymize
     * @param timeQuality
     * @param timePostprocess
     */
    public ARXDistributedResult(Data data,
                                long timePrepare,
                                long timeComplete,
                                long timeAnonymize,
                                long timeGlobalTransform,
                                long timePartitionByClass,
                                long timeSuppress,
                                long timeQuality,
                                long timePostprocess) {
        this(data, timePrepare, timeComplete, timeAnonymize, timeGlobalTransform, timePartitionByClass, timeSuppress, timeQuality, timePostprocess, null, Long.MIN_VALUE, 0);
    }

    /**
     * Creates a new instance
     * @param data
     * @param timePrepare
     * @param timeComplete
     * @param timeQuality
     * @param timePostprocess
     * @param qualityMetrics
     * @param maxMemoryConsumption
     */
    public ARXDistributedResult(Data data, 
                                long timePrepare, 
                                long timeComplete,
                                long timeAnon,
                                long timeGlobalTransform,
                                long timePartitionByClass,
                                long timeSuppress,
                                long timeQuality,
                                long timePostprocess,
                                Map<String, List<Double>> qualityMetrics,
                                long maxMemoryConsumption,
                                long numberOfMemoryMeasurements) {
        
        // Store
        this.timePrepare = timePrepare;
        this.timeComplete = timeComplete;
        this.timeAnonymize = timeAnon;
        this.timeGlobalTransform = timeGlobalTransform;
        this.timePartitionByClass = timePartitionByClass;
        this.timeSuppress = timeSuppress;
        this.timeQuality = timeQuality;
        this.timePostprocess = timePostprocess;
        this.maxMemoryConsumption = maxMemoryConsumption;
        this.numberOfMemoryMeasurements = numberOfMemoryMeasurements;
        this.data = data;
        
        // Collect statistics
        if (qualityMetrics != null) {
            this.qualityMetrics.putAll(qualityMetrics);
        }
    }


    /**
     * Returns the maximum memory consumed in bytes
     * @return the max memory consumed in bytes
     */
    public long getMaxMemoryConsumption() {
        return maxMemoryConsumption;
    }

    /**
     * Returns a handle to the data obtained by applying the optimal transformation. This method will fork the buffer, 
     * allowing to obtain multiple handles to different representations of the data set. Note that only one instance can
     * be obtained for each transformation.
     * 
     * @return
     */
    public DataHandle getOutput() {
        return data.getHandle();
    }
    
    
    /**
     * Returns quality estimates
     * @return
     */
    public Map<String, List<Double>> getQuality() {
        return qualityMetrics;
    }


    /**
     * Returns the time needed for complete anonymization
     * @return the timeAnonymize
     */
    public long getTimeComplete() {
        return timeComplete;
    }

    /**
     * Returns the time needed for first anonymization step
     * @return the timeAnonymize
     */
    public long getTimeAnonymize() {
        return timeAnonymize;
    }

    /**
     * Returns the time needed for global transformation step
     * @return the timeAnonymize
     */
    public long getTimeGlobalTransform() {
        return timeGlobalTransform;
    }

    /**
     * Returns the time needed for partition by class step
     * @return the timeAnonymize
     */
    public long getTimePartitionByClass() {
        return timePartitionByClass;
    }

    /**
     * Returns the time needed for anonymization with suppression step
     * @return the timeAnonymize
     */
    public long getTimeSuppress() {
        return timeSuppress;
    }

    /**
     * Returns the time needed for step timeQuality of anonymization
     * @return the timePostprocess
     */
    public double getTimeQuality() {
        return timeQuality;
    }

    /**
     * Returns the time needed for postprocessing
     * @return the timePostprocess
     */
    public long getTimePostprocess() {
        return timePostprocess;
    }

    /**
     * Returns the time needed for preparation
     * @return the timePrepare
     */
    public long getTimePrepare() {
        return timePrepare;
    }
    
    /**
     * Returns whether max memory measurement is available
     * @return
     */
    public boolean isMaxMemoryAvailable() {
        return maxMemoryConsumption != Long.MIN_VALUE;
    }

    public long getNumberOfMemoryMeasurements() {
        return numberOfMemoryMeasurements;
    }
}
