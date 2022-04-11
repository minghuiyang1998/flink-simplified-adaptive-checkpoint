package org.apache.flink.runtime.jobmaster;

import org.apache.flink.runtime.checkpoint.CheckpointCoordinator;
import org.apache.flink.runtime.jobgraph.tasks.CheckpointCoordinatorConfiguration;
import org.apache.flink.runtime.jobgraph.tasks.JobCheckpointAdapterConfiguration;
import org.apache.flink.runtime.taskmanager.TaskManagerRunningState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class CheckpointAdapter {
    final class ConsumerRange implements Runnable {
        @Override
        public void run() {
            while(isAdapterEnable) {
                if(queue.size() > 0) {
                    try {
                        long p = queue.take() * 1000; // transfer to ms
                        if (isOverAllowRange(p)) {
                            log.info("over allowRange, change checkpoint interval");
                            updatePeriod(p);
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    final class ConsumerPeriod implements Runnable {
        private long minPeriod = Long.MAX_VALUE;
        private final Timer timer = new Timer();

        @Override
        public void run() {
            timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    log.info(changeInterval + " has passed, change checkpoint interval!");
                    updatePeriod(minPeriod);
                }
            }, changeInterval, changeInterval);

            // deal with data as much as it can in one period
            while(isAdapterEnable) {
                if(queue.size() > 0) {
                    try {
                        long p = queue.take() * 1000; // transfer to ms
                        minPeriod = Math.min(p, minPeriod);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    final class ConsumerRangePeriod implements Runnable {
        private long minPeriod = Long.MAX_VALUE;
        private final Timer timer = new Timer();

        @Override
        public void run() {
            timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    log.info(changeInterval + " has passed, change checkpoint interval!");
                    updatePeriod(minPeriod);
                }
            }, changeInterval, changeInterval);

            while(isAdapterEnable) {
                if(queue.size() > 0) {
                    try {
                        long p = queue.take() * 1000; // transfer to ms
                        if (isOverAllowRange(p)) {
                            log.info("over allowRange, store minPeriod");
                            minPeriod = Math.min(p, minPeriod);
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    final class ConsumerDebounce implements Runnable {
        private long minPeriod = Long.MAX_VALUE;
        private final Timer timer = new Timer();

        @Override
        public void run() {
            while(isAdapterEnable) {
                if(queue.size() > 0) {
                    try {
                        long p = queue.take() * 1000; // transfer to ms
                        if (isOverAllowRange(p)) {
                            log.info("over allowRange, store minPeriod");
                            minPeriod = Math.min(p, minPeriod);
                            timer.cancel();
                            log.info("start timer");
                            timer.schedule(new TimerTask() {
                                @Override
                                public void run() {
                                    log.info("start timer and change interval after " + changeInterval);
                                    updatePeriod(minPeriod);
                                }
                            }, changeInterval);
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private JobCheckpointAdapterConfiguration checkpointAdapterConfiguration;
    private long baseInterval;
    private final CheckpointCoordinator coordinator;
    private boolean isAdapterEnable;
    private final BlockingQueue<Long> queue;

    private final long recoveryTime;
    private final double allowRange;
    private final long changeInterval;

    protected final Logger log = LoggerFactory.getLogger(getClass());


    public CheckpointAdapter(
            CheckpointCoordinatorConfiguration chkConfig,
            JobCheckpointAdapterConfiguration checkpointAdapterConfiguration,
            CheckpointCoordinator coordinator) {
        this.checkpointAdapterConfiguration = checkpointAdapterConfiguration;
        this.coordinator = coordinator;
        this.baseInterval = chkConfig.getCheckpointInterval();
        this.isAdapterEnable = true;
        this.queue = new LinkedBlockingQueue<>();

        this.recoveryTime = checkpointAdapterConfiguration.getRecoveryTime();
        this.allowRange = checkpointAdapterConfiguration.getAllowRange();
        this.changeInterval = checkpointAdapterConfiguration.getChangeInterval();
        boolean isDebounceMode = checkpointAdapterConfiguration.isDebounceMode();

        boolean withPeriod = changeInterval > 0;
        boolean withRange = allowRange > 0;
        log.info("changeInterval:" + changeInterval);
        log.info("allowRange:" + allowRange);
        log.info("isDebounceMode:" + isDebounceMode);
        if (withPeriod || withRange) {
            ThreadPoolExecutor executor = new ThreadPoolExecutor(3, 10, 60,
                    TimeUnit.SECONDS, new ArrayBlockingQueue<>(20));
            Runnable consumer;
            if (withPeriod && withRange) {
                if (isDebounceMode) {
                    log.info("set up a <Debounce> consumer");
                    consumer = new ConsumerDebounce();
                } else {
                    log.info("set up a <RangePeriod> consumer");
                    consumer = new ConsumerRangePeriod();
                }
            } else if (withPeriod) {
                log.info("set up a <Period> consumer");
                consumer = new ConsumerPeriod();
            } else {
                log.info("set up a <Period> consumer");
                consumer = new ConsumerRange();
            }
            CompletableFuture.runAsync(consumer, executor).thenRunAsync(executor::shutdown);
        }
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
        if (Double.isNaN(ideal) || Double.isNaN(inputRate)) {
            return true;
        }

        double maxData = (double) (recoveryTime / 1000) * ideal; // ideal: records per second
        long newPeriod = (long) (maxData / inputRate); // (records / million seconds)

        // Get rid of extreme data
        if (newPeriod == 0 || newPeriod == Long.MAX_VALUE) {
            return true;
        }

        try {
            queue.put(newPeriod);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return true;
    }

    private boolean isOverAllowRange(long period) {
        long variation = (period - baseInterval) / baseInterval;
        return variation > allowRange;
    }

    private void updatePeriod(long newPeriod) {
        // update when a checkpoint is completed
        coordinator.restartCheckpointScheduler(newPeriod);
        baseInterval = newPeriod;
        final String message = "Current Checkpoint Interval was changed to: "
                + baseInterval;
        log.info(message);
    }

    public JobCheckpointAdapterConfiguration getCheckpointAdapterConfiguration() {
        return checkpointAdapterConfiguration;
    }

    public CheckpointCoordinator getCoordinator() {
        return coordinator;
    }
}
