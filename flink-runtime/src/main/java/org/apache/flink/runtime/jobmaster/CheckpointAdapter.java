package org.apache.flink.runtime.jobmaster;

import org.apache.flink.runtime.checkpoint.CheckpointCoordinator;
import org.apache.flink.runtime.jobgraph.tasks.JobCheckpointAdapterConfiguration;

public class CheckpointAdapter {
    private JobCheckpointAdapterConfiguration checkpointAdapterConfiguration;
    private CheckpointCoordinator coordinator;

    public CheckpointAdapter(
            JobCheckpointAdapterConfiguration checkpointAdapterConfiguration,
            CheckpointCoordinator coordinator) {
        this.checkpointAdapterConfiguration = checkpointAdapterConfiguration;
        this.coordinator = coordinator;
    }

    public void setCheckpointAdapterConfiguration(JobCheckpointAdapterConfiguration checkpointAdapterConfiguration) {
        this.checkpointAdapterConfiguration = checkpointAdapterConfiguration;
    }

    public void dealWithMessageFromOneTaskExecutor() {

    }

    public void updatePeriod() {
        // TODO: restart function in CheckpointCoordinator
//        coordinator.restartCheckpointScheduler(min);
    }
}
