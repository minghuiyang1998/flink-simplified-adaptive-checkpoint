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

package org.apache.flink.runtime.taskmanager;

import org.apache.flink.metrics.Meter;
import org.apache.flink.runtime.executiongraph.ExecutionAttemptID;
import org.apache.flink.runtime.metrics.groups.TaskIOMetricGroup;

import java.io.Serializable;

/**
 * This class represents an update about a task's execution state.
 *
 * <p><b>NOTE:</b> The exception that may be attached to the state update is not necessarily a Flink
 * or core Java exception, but may be an exception from the user code. As such, it cannot be
 * deserialized without a special class loader. For that reason, the class keeps the actual
 * exception field transient and deserialized it lazily, with the appropriate class loader.
 */
public class TaskManagerRunningState implements Serializable {

    private static final long serialVersionUID = 1L;

    private final ExecutionAttemptID executionId;

    /** Serialized user-defined accumulators */
    private final TaskIOMetricGroup taskIOMetricGroup;

    private final double numRecordsInRate;

    private final double idealProcessingRate;

    private final long checkpointID;

    /**
     * Creates a new task execution state update, with an attached exception. This constructor may
     * never throw an exception.
     *
     * @param executionId the ID of the task execution whose state is to be reported
     * @param taskIOMetricGroup The flink and user-defined accumulators which may be null.
     */
    public TaskManagerRunningState(
            ExecutionAttemptID executionId,
            long checkpointID,
            TaskIOMetricGroup taskIOMetricGroup) {

        if (executionId == null || taskIOMetricGroup == null) {
            throw new NullPointerException();
        }

        this.executionId = executionId;
        this.taskIOMetricGroup = taskIOMetricGroup;
        Meter numRecordsInRate = taskIOMetricGroup.getNumRecordsInRate();
        this.numRecordsInRate = numRecordsInRate.getRate();
        double busyTimeMsPerSecond = taskIOMetricGroup.getBusyTimePerSecond();
        this.idealProcessingRate = this.numRecordsInRate * 1000 / busyTimeMsPerSecond;
        this.checkpointID = checkpointID;
    }

    public ExecutionAttemptID getExecutionId() {
        return executionId;
    }

    public TaskIOMetricGroup getTaskIOMetricGroup() {
        return taskIOMetricGroup;
    }

    public double getNumRecordsInRate() {
        return numRecordsInRate;
    }

    public double getIdealProcessingRate() {
        return idealProcessingRate;
    }

    public long getCheckpointID() {
        return checkpointID;
    }
}
