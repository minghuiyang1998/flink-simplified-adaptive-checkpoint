package org.apache.flink.streaming.examples.tuplejob;

import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.util.Collector;

public class TupleCounter {
    // set up the streaming execution environment
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        DataStreamSource<Tuple2<String, Integer>> source =
                env.addSource(new TupleSource());
        source.keyBy(0)
                .flatMap(new ValueStateSum())
                .print();
        env.execute("TupleCounter");
    }
}

class ValueStateSum extends RichFlatMapFunction<Tuple2<String, Integer>, Tuple2<String, Integer>> {
    private ValueState<Tuple2<Integer, Integer>> valueState;

    @Override
    public void open(Configuration parameters) throws Exception {
        ValueStateDescriptor<Tuple2<Integer, Integer>> descriptor = new ValueStateDescriptor<Tuple2<Integer, Integer>>(
                "valueStateName",
                Types.TUPLE(Types.STRING, Types.INT));
        valueState = getRuntimeContext().getState(descriptor);
    }

    @Override
    public void flatMap(Tuple2<String, Integer> element,
                        Collector<Tuple2<String, Integer>> out) throws Exception {
        Tuple2<Integer, Integer> currentState = valueState.value();

        if (currentState == null) {
            currentState = Tuple2.of(0, 0);
        }
        currentState.f0 += 1;
        currentState.f1 += element.f1;
        valueState.update(currentState);
        out.collect(Tuple2.of(element.f0, currentState.f1));
    }
}
