package org.apache.flink.streaming.api.environment;

import static org.apache.flink.util.Preconditions.checkNotNull;

public class AdapterConfig {
    /** the same as user Tolerant time. */
    private long recoveryTime = -1;

    /**
     * Creates a deep copy of the provided {@link AdapterConfig}.
     *
     * @param adapterConfig the config to copy.
     */
    public AdapterConfig(final AdapterConfig adapterConfig) {
        checkNotNull(adapterConfig);
        this.recoveryTime = adapterConfig.recoveryTime;
    }

    public AdapterConfig() {}

    /**
     * Sets the recovery time in which adapter used for new period calculation
     * @param recoveryTime The recovery time, in milliseconds.
     */
    public void setRecoveryTime(long recoveryTime) {
        this.recoveryTime = recoveryTime;
    }

    public long getRecoveryTime() {
        return recoveryTime;
    }
}
