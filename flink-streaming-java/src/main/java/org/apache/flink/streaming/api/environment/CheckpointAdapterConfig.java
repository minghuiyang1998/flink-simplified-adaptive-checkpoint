package org.apache.flink.streaming.api.environment;

import org.apache.flink.annotation.Public;

import static org.apache.flink.util.Preconditions.checkNotNull;

/** Configuration that captures all adapter related settings. */
@Public
public class CheckpointAdapterConfig {
    /** the same as user Tolerant time. */
    private long recoveryTime = -1;

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
