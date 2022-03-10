package org.apache.flink.runtime.jobmaster;

import org.apache.flink.runtime.checkpoint.CheckpointCoordinator;
import org.apache.flink.runtime.jobgraph.tasks.CheckpointCoordinatorConfiguration;
import org.apache.flink.runtime.jobgraph.tasks.JobCheckpointAdapterConfiguration;
import org.apache.flink.runtime.taskmanager.TaskManagerRunningState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class CheckpointAdapter {
    private JobCheckpointAdapterConfiguration checkpointAdapterConfiguration;
    private long baseInterval;

    private final CheckpointCoordinator coordinator;
    private final long recoveryTime;
    private boolean isAdapterEnable;
    private final BlockingQueue<Long> queue;
    private final double diff;

    protected final Logger log = LoggerFactory.getLogger(getClass());

    final class Consumer implements Runnable {
        @Override
        public void run() {
            while(isAdapterEnable) {
                if(queue.size() > 0) {
                    try {
                        long p = queue.take() * 1000; // transfer to ms
                        long variation = (p - baseInterval) / baseInterval;
                        if (variation > diff) {
                            final String message = "Current Checkpoint Interval: "
                                            + baseInterval;
                            log.info(message);
                            updatePeriod(p);
                            baseInterval = p;
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    public CheckpointAdapter(
            CheckpointCoordinatorConfiguration chkConfig,
            JobCheckpointAdapterConfiguration checkpointAdapterConfiguration,
            CheckpointCoordinator coordinator) {

        this.checkpointAdapterConfiguration = checkpointAdapterConfiguration;
        this.coordinator = coordinator;
        this.baseInterval = chkConfig.getCheckpointInterval();
        this.recoveryTime =
                checkpointAdapterConfiguration == null
                        ? 5000L
                        : checkpointAdapterConfiguration.getRecoveryTime();
        this.isAdapterEnable = true;
        this.diff = 0.5;
        this.queue = new LinkedBlockingQueue<>();

        ThreadPoolExecutor executor = new ThreadPoolExecutor(3, 10, 60,
                TimeUnit.SECONDS, new ArrayBlockingQueue<>(20));
        Consumer consumer = new Consumer();
        CompletableFuture.runAsync(consumer, executor).thenRunAsync(executor::shutdown);
    }

    public void setAdapterEnable(boolean adapterEnable) {
        isAdapterEnable = adapterEnable;
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
                "ideal: "
                        + ideal
                        + " inputRate: "
                        + inputRate
                        + " checkpointID: "
                        + checkpointID;
        log.info(message);

        // dealt with initial NaN
        if (Double.isNaN(ideal) || Double.isNaN(inputRate)) return true;

        double maxData = (double) (recoveryTime / 1000) * ideal; // ideal: records per second
        long newPeriod = (long) (maxData / inputRate); // (records / million seconds)

        // Get rid of extreme data
        if (newPeriod == 0 || newPeriod == Long.MAX_VALUE) return true;

        try {
            queue.put(newPeriod);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return true;
    }

    public void updatePeriod(long newPeriod) {
        // update when a checkpoint is completed
        coordinator.restartCheckpointScheduler(newPeriod);
        final String message =
                "calculated period is 50% different from current period, "
                        + "checkpoint Period has been changed to: "
                        + newPeriod;
        log.info(message);
    }

    public JobCheckpointAdapterConfiguration getCheckpointAdapterConfiguration() {
        return checkpointAdapterConfiguration;
    }

    public CheckpointCoordinator getCoordinator() {
        return coordinator;
    }
}
