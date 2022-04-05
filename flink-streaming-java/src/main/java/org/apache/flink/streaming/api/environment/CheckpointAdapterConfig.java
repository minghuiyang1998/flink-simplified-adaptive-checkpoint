package org.apache.flink.streaming.api.environment;

import org.apache.flink.annotation.Public;

import static org.apache.flink.util.Preconditions.checkNotNull;

/** Configuration that captures all adapter related settings. */
@Public
public class CheckpointAdapterConfig {
    /** the same as user tolerant time. */
    private long recoveryTime = -1;
    /** The interval between data submissions of taskExecutor,
     * The default timing is -1 , which means commit once after completing a checkpoint */
    private long metricsInterval = -1;
    /** A new period calculated from the metrics outside this range
     * triggers a period change operation
     * default value is 10%
     * */
    private double allowRange = -1;
    /** The modification is performed only once in a time range,
     * If this value is not set and only allowRange is set,
     * changes will be triggered as soon as they occur.
     * If only this value is set, period is reset with the smallest
     * value for each cycle, regardless of whether an out-of-range
     * change has occurred
     * */
    private long changeInterval = -1;
    /** If this value is true. You must both set allowRange and changePeriod
     * Changes are triggered only if the data stays at a level (allowRange)
     * for a period of time (changePeriod)
     * */
    private boolean isDebounceMode = false;

    public void setAllowRange(double allowRange) {
        this.allowRange = allowRange;
    }

    public void setChangeInterval(long changeInterval) {
        this.changeInterval = changeInterval;
    }

    public void setDebounceMode(boolean debounceMode) {
        isDebounceMode = debounceMode;
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

    public void setMetricsInterval(long metricsInterval) {
        this.metricsInterval = metricsInterval;
    }

    public long getMetricsInterval() {
        return metricsInterval;
    }

    public boolean isCheckpointAdapterEnabled() {
        return recoveryTime > 0;
    }

    /**
     * Creates a deep copy of the provided {@link CheckpointAdapterConfig}.
     *
     * @param checkpointAdapterConfig the config to copy.
     */
    public CheckpointAdapterConfig(final CheckpointAdapterConfig checkpointAdapterConfig) {
        checkNotNull(checkpointAdapterConfig);
        this.recoveryTime = checkpointAdapterConfig.recoveryTime;
    }

    public CheckpointAdapterConfig() {}

    /**
     * Sets the recovery time in which adapter used for new period calculation.
     *
     * @param recoveryTime The recovery time, in milliseconds.
     */
    public void setRecoveryTime(long recoveryTime) {
        this.recoveryTime = recoveryTime;
    }

    public long getRecoveryTime() {
        return recoveryTime;
    }
}
