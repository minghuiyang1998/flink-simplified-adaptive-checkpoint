/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.jobgraph.tasks;

import java.io.Serializable;

/**
 * The JobCheckpointingAdapterSettings are attached to a JobGraph and describe the settings for the
 * asynchronous checkpoints of the JobGraph, such as recovery time.
 */
public class JobCheckpointAdapterConfiguration implements Serializable {
    public static final long DEFAULT_RECOVERY = 10000;
    /** the same as user tolerant time. */
    private final long recoveryTime;
    /**
     * The interval between data submissions of taskExecutor, The default timing is -1 , which means
     * commit once after completing a checkpoint
     */
    private long metricsInterval;
    /**
     * A new period calculated from the metrics outside this range triggers a period change
     * operation default value is 10%
     */
    private double allowRange;
    /**
     * The modification is performed only once in a time range, If this value is not set and only
     * allowRange is set, changes will be triggered as soon as they occur. If only this value is
     * set, period is reset with the smallest value for each cycle, regardless of whether an
     * out-of-range change has occurred
     */
    private long changeInterval;
    /**
     * If this value is true. You must both set allowRange and changePeriod Changes are triggered
     * only if the data stays at a level (allowRange) for a period of time (changePeriod)
     */
    private boolean isDebounceMode;

    public long getRecoveryTime() {
        return recoveryTime;
    }

    public long getMetricsInterval() {
        return metricsInterval;
    }

    public double getAllowRange() {
        return allowRange;
    }

    public long getChangeInterval() {
        return changeInterval;
    }

    public boolean isDebounceMode() {
        return isDebounceMode;
    }

    public boolean isAdapterEnable() {
        return recoveryTime > 0;
    }

    public void setMetricsInterval(long metricsInterval) {
        this.metricsInterval = metricsInterval;
    }

    public void setAllowRange(double allowRange) {
        this.allowRange = allowRange;
    }

    public void setChangeInterval(long changeInterval) {
        this.changeInterval = changeInterval;
    }

    public void setDebounceMode(boolean debounceMode) {
        isDebounceMode = debounceMode;
    }

    public JobCheckpointAdapterConfiguration(long recoveryTime) {
        this.recoveryTime = recoveryTime;
    }

    public JobCheckpointAdapterConfiguration(
            long recoveryTime,
            long metricsInterval,
            double allowRange,
            long changeInterval,
            boolean isDebounceMode) {
        this.recoveryTime = recoveryTime;
        this.metricsInterval = metricsInterval;
        this.allowRange = allowRange;
        this.changeInterval = changeInterval;
        this.isDebounceMode = isDebounceMode;
    }

    public JobCheckpointAdapterConfiguration() {
        this.recoveryTime = DEFAULT_RECOVERY;
    }
}
