package org.apache.flink.streaming.examples.tuplejob;

import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.streaming.api.operators.StreamSink;

import org.apache.flink.streaming.runtime.streamrecord.LatencyMarker;

import org.slf4j.Logger;

public class LatencySink<T> extends StreamSink<T> {

    private final Logger logger;

    public LatencySink(Logger logger) {
        super(new SinkFunction() {
            @Override
            public void invoke(Object value, Context ctx) {

            }
        });
        this.logger = logger;
    }

    @Override
    public void processLatencyMarker(LatencyMarker latencyMarker) throws Exception {
        logger.warn("%{}%{}", "latency", System.currentTimeMillis() - latencyMarker.getMarkedTime());
    }
}
