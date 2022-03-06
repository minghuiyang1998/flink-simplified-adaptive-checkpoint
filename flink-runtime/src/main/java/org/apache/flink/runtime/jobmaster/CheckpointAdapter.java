package org.apache.flink.runtime.jobmaster;

import org.apache.flink.runtime.checkpoint.CheckpointCoordinator;
import org.apache.flink.runtime.jobgraph.tasks.JobCheckpointAdapterConfiguration;
import org.apache.flink.runtime.taskmanager.TaskManagerRunningState;

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

    public boolean dealWithMessageFromOneTaskExecutor(TaskManagerRunningState taskManagerRunningState) {
        double ideal = taskManagerRunningState.getIdealProcessingRate();
        double inputRate = taskManagerRunningState.getNumRecordsInRate();
        // TODO: do something here

        return true;
    }

    public void updatePeriod() {
        // TODO: rewrite a  function in CheckpointCoordinator to restart
//        coordinator.restartCheckpointScheduler(min);
    }

    public JobCheckpointAdapterConfiguration getCheckpointAdapterConfiguration() {
        return checkpointAdapterConfiguration;
    }

    public CheckpointCoordinator getCoordinator() {
        return coordinator;
    }
}
