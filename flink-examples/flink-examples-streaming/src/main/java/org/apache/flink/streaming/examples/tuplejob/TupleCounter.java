package org.apache.flink.streaming.examples.tuplejob;

import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.tuple.Tuple;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.typeutils.GenericTypeInfo;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.state.hashmap.HashMapStateBackend;
import org.apache.flink.runtime.state.storage.JobManagerCheckpointStorage;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSink;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.Collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TupleCounter {
    // set up the streaming execution environment
    private static final Logger logger = LoggerFactory.getLogger(TupleCounter.class);

    public static void main(String[] args) throws Exception {
        GenericTypeInfo<Object> objectTypeInfo = new GenericTypeInfo<>(Object.class);
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(3000L);
//        env.enableCheckpointAdapter(2000L);
        env.getCheckpointConfig().enableExternalizedCheckpoints(
                CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
        env.setStateBackend(new HashMapStateBackend());
        env.getCheckpointConfig().setCheckpointStorage(new JobManagerCheckpointStorage());

        env.getConfig().setLatencyTrackingInterval(10000);

        // src
        DataStreamSource<Tuple2<String, Integer>> src =
                env.addSource(new TupleSource());

        // mapper and counter
        SingleOutputStreamOperator<Tuple2<String, Integer>> tuple2Stream =
                src
                        .keyBy(value -> value.f0)
                        .flatMap(new ValueStateSum());

        // tracking throughput operator
        tuple2Stream.map(new ThroughputTracking());

        // sink
        tuple2Stream.flatMap(new ValueStateSum())
                .transform("LatencySink", objectTypeInfo, new LatencySink<>(logger));

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


