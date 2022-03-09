package org.apache.flink.runtime.jobmaster;

import org.apache.flink.runtime.checkpoint.CheckpointCoordinator;
import org.apache.flink.runtime.jobgraph.tasks.JobCheckpointAdapterConfiguration;
import org.apache.flink.runtime.taskmanager.TaskManagerRunningState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;

public class CheckpointAdapter {
    private JobCheckpointAdapterConfiguration checkpointAdapterConfiguration;
    private CheckpointCoordinator coordinator;
    protected final Logger log = LoggerFactory.getLogger(getClass());
    private long recoveryTime;
    private ConcurrentMap<Long, BlockingQueue<Long>> ckpToPeriods;

    public CheckpointAdapter(
            JobCheckpointAdapterConfiguration checkpointAdapterConfiguration,
            CheckpointCoordinator coordinator) {
        this.checkpointAdapterConfiguration = checkpointAdapterConfiguration;
        this.coordinator = coordinator;
        this.recoveryTime = checkpointAdapterConfiguration == null ? 5000l :
                checkpointAdapterConfiguration.getRecoveryTime();
        this.ckpToPeriods = new ConcurrentHashMap<>();
    }

    public void setCheckpointAdapterConfiguration(
            JobCheckpointAdapterConfiguration checkpointAdapterConfiguration) {
        this.checkpointAdapterConfiguration = checkpointAdapterConfiguration;
    }

    public boolean dealWithMessageFromOneTaskExecutor(
            TaskManagerRunningState taskManagerRunningState) {
        double ideal = taskManagerRunningState.getIdealProcessingRate();
        double inputRate = taskManagerRunningState.getNumRecordsInRate();
        long checkpointID = taskManagerRunningState.getCheckpointID();

        final String message =
                String.format("ideal: " + ideal + " inputRate: " + inputRate
                        + " checkpointID: " + checkpointID);
        log.info(message);
        System.out.println("ideal: " + ideal + " inputRate: " +
                inputRate + " checkpointID: " + checkpointID);

        double maxData = recoveryTime * ideal;
        long newPeriod = (long)(maxData / inputRate);
        try {
            ckpToPeriods.putIfAbsent(checkpointID, new LinkedBlockingQueue<>());
            ckpToPeriods.get(checkpointID).put(newPeriod);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return true;
    }

    public void updatePeriod(long checkpointId) {
        // update when a checkpoint is completed
        long min = Long.MAX_VALUE;
        BlockingQueue<Long> queue = ckpToPeriods.get(checkpointId);
        while (queue.size() > 0) {
            try {
                Long period = queue.take();
                min = Math.min(min, period);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        coordinator.restartCheckpointScheduler(min);
    }

    public JobCheckpointAdapterConfiguration getCheckpointAdapterConfiguration() {
        return checkpointAdapterConfiguration;
    }

    public CheckpointCoordinator getCoordinator() {
        return coordinator;
    }
}
