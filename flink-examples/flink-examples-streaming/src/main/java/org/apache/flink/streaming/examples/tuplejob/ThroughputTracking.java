package org.apache.flink.streaming.examples.tuplejob;

import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.dropwizard.metrics.DropwizardMeterWrapper;
import org.apache.flink.metrics.Meter;

public class ThroughputTracking extends RichMapFunction<Tuple2<String, Integer>, Object> {
    private transient Meter meter;

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);

        this.meter = getRuntimeContext()
                .getMetricGroup()
                .meter("myMeter",
                        new DropwizardMeterWrapper(new com.codahale.metrics.Meter()));
    }

    @Override
    public Object map(Tuple2<String, Integer> value) throws Exception {
        this.meter.markEvent();
        return value;
    }
}
