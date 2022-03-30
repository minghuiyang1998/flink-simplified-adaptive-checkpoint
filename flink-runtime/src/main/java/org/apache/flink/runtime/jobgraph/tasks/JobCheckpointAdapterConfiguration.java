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
    private final long recoveryTime;
    private long metricsInterval;
    private double allowRange;
    private long changeInterval;
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
