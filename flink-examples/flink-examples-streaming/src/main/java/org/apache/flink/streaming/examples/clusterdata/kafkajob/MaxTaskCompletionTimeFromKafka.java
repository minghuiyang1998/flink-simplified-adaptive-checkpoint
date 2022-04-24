package org.apache.flink.streaming.examples.clusterdata.kafkajob;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.typeutils.GenericTypeInfo;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.KafkaSourceOptions;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.runtime.state.hashmap.HashMapStateBackend;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.streaming.api.operators.StreamSink;
import org.apache.flink.streaming.examples.clusterdata.datatypes.EventType;
import org.apache.flink.streaming.examples.clusterdata.datatypes.TaskEvent;
import org.apache.flink.streaming.examples.clusterdata.utils.AppBase;
import org.apache.flink.streaming.examples.clusterdata.utils.TaskEventSchema;
import org.apache.flink.streaming.runtime.streamrecord.LatencyMarker;
import org.apache.flink.util.Collector;

import com.esotericsoftware.minlog.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.flink.streaming.api.CheckpointingMode.EXACTLY_ONCE;

/** Other ideas: - average task runtime per priority - a histogram of task scheduling latency. */
public class MaxTaskCompletionTimeFromKafka extends AppBase {
    private static final Logger logger =
            LoggerFactory.getLogger(MaxTaskCompletionTimeFromKafka.class);

    private static final String LOCAL_KAFKA_BROKER = "localhost:9092";
    private static final String REMOTE_KAFKA_BROKER = "20.127.226.8:9092";
    private static final String TASKS_GROUP = "task_group_web4";
    public static final String TASKS_TOPIC = "wiki-edits";
    public static final String CHECKPOINT_DIR = "file:///home/CS551Team2/Checkpoint";

    public static void main(String[] args) throws Exception {
        GenericTypeInfo<Object> objectTypeInfo = new GenericTypeInfo<>(Object.class);

        // set up streaming execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        //        env.enableCheckpointAdapter(10000L);
        //        env.setCheckpointAdapterMetricInterval(5000L);
        //        env.setCheckpointAdapterAllowRange(0.4);
        //  env.setParallelism(2);

        // set up checkpointing
        env.enableCheckpointing(10000L, EXACTLY_ONCE);

        // TODO: statebackend here
        env.setStateBackend(new HashMapStateBackend());
        env.getCheckpointConfig().setCheckpointStorage(CHECKPOINT_DIR);

        KafkaSource<TaskEvent> source =
                KafkaSource.<TaskEvent>builder()
                        .setBootstrapServers(REMOTE_KAFKA_BROKER)
                        .setGroupId(TASKS_GROUP)
                        .setTopics(TASKS_TOPIC)
                        .setDeserializer(
                                KafkaRecordDeserializationSchema.valueOnly(new TaskEventSchema()))
                        // TODO: adjust speed by control poll records
                        .setProperty(
                                KafkaSourceOptions.REGISTER_KAFKA_CONSUMER_METRICS.key(), "true")
                        // If each partition has a committed offset, the offset will be consumed
                        // from the committed offset.
                        // Start consuming from scratch when there is no submitted offset
                        .setStartingOffsets(OffsetsInitializer.earliest())
                        .build();
        DataStream<TaskEvent> events =
                env.fromSource(source, WatermarkStrategy.noWatermarks(), "Kafka source");

        // get SUBMIT and FINISH events in one place "jobId", "taskIndex", out put task duration
        DataStream<Tuple3<Long, Integer, Long>> taskDurations =
                events.keyBy(
                                new KeySelector<TaskEvent, Tuple2<Integer, Long>>() {
                                    @Override
                                    public Tuple2<Integer, Long> getKey(TaskEvent taskEvent)
                                            throws Exception {
                                        return Tuple2.of(taskEvent.taskIndex, taskEvent.jobId);
                                    }
                                })
                        .flatMap(new CalculateTaskDuration());
        // disableChaining();

        DataStream<Tuple2<Long, Long>> maxDurationsPerJob =
                taskDurations
                        // key by priority
                        .keyBy(
                                new KeySelector<
                                        Tuple3<Long, Integer, Long>, Tuple2<Long, Integer>>() {
                                    @Override
                                    public Tuple2<Long, Integer> getKey(
                                            Tuple3<Long, Integer, Long> task) throws Exception {
                                        return Tuple2.of(task.f0, task.f1);
                                    }
                                })
                        .flatMap(new TaskWithMinDurationPerJob());
        // disableChaining();

        maxDurationsPerJob.transform("Latency Sink", objectTypeInfo, new LatencySink<>(logger));

        env.execute();
    }

    private static final class TaskWithMinDurationPerJob
            extends RichFlatMapFunction<Tuple3<Long, Integer, Long>, Tuple2<Long, Long>> {

        private MapState<Long, Long> minDurationPerJob;
        private ValueState<Integer> countCompletedTasks;

        @Override
        public void open(Configuration parameters) throws Exception {
            minDurationPerJob =
                    getRuntimeContext()
                            .getMapState(
                                    new MapStateDescriptor<>("max-map", Long.class, Long.class));
            countCompletedTasks =
                    getRuntimeContext()
                            .getState(new ValueStateDescriptor<>("count-value", Integer.class));
        }

        @Override
        public void flatMap(Tuple3<Long, Integer, Long> task, Collector<Tuple2<Long, Long>> out)
                throws Exception {
            // <job, maxTask>
            Integer count = countCompletedTasks.value();
            if (count == null) {
                countCompletedTasks.update(1);
            } else {
                countCompletedTasks.update(count + 1);
            }
            Long currJobId = task.f0;
            Long duration = task.f2;
            Long minInJob = minDurationPerJob.get(currJobId);
            if (minInJob == null) {
                minInJob = duration;
            } else {
                minInJob = Math.min(minInJob, duration);
            }
            minDurationPerJob.put(currJobId, minInJob);
            out.collect(new Tuple2<>(currJobId, minInJob));
        }
    }

    private static final class CalculateTaskDuration
            extends RichFlatMapFunction<TaskEvent, Tuple3<Long, Integer, Long>> {

        // a map of "task => event"
        // keep all values in state
        private MapState<Tuple2<Long, Integer>, TaskEvent> events;
        private ValueState<Integer> countMessages;

        @Override
        public void open(Configuration parameters) throws Exception {
            events =
                    getRuntimeContext()
                            .getMapState(
                                    new MapStateDescriptor<>(
                                            "duration-map",
                                            TypeInformation.of(
                                                    new TypeHint<Tuple2<Long, Integer>>() {}),
                                            TypeInformation.of(
                                                    new TypeHint<TaskEvent>() {
                                                        @Override
                                                        public TypeInformation<TaskEvent>
                                                                getTypeInfo() {
                                                            return super.getTypeInfo();
                                                        }
                                                    })));
            countMessages =
                    getRuntimeContext()
                            .getState(new ValueStateDescriptor<>("msg-value", Integer.class));
        }

        @Override
        public void flatMap(TaskEvent taskEvent, Collector<Tuple3<Long, Integer, Long>> out)
                throws Exception {
            Tuple2<Long, Integer> taskKey = new Tuple2<>(taskEvent.jobId, taskEvent.taskIndex);
            Log.info("jobID: " + taskEvent.jobId + " taskIndex: " + taskEvent.taskIndex);
            Integer count = countMessages.value();
            if (count == null) {
                countMessages.update(1);
            } else {
                countMessages.update(count + 1);
            }

            // what's the event type?
            if (taskEvent.eventType.equals(EventType.SUBMIT)) {
                // check if there is already a SUBMIT for this task and if yes, update it
                if (events.contains(taskKey)) {
                    TaskEvent stored = events.get(taskKey);
                    if (stored.eventType.equals(EventType.SUBMIT)) {
                        // keep latest SUBMIT
                        if (stored.timestamp < taskEvent.timestamp) {
                            events.put(taskKey, taskEvent);
                        }
                    } else {
                        // stored event is a FINISH => output the duration
                        out.collect(
                                new Tuple3<>(
                                        taskEvent.jobId,
                                        taskEvent.taskIndex,
                                        stored.timestamp - taskEvent.timestamp));
                        // clean-up state
                        events.remove(taskKey);
                    }
                } else {
                    // first SUBMIT we see for this task
                    events.put(taskKey, taskEvent);
                }
            } else {
                // this is a FINISH event: compute duration and emit downstream
                if (events.contains(taskKey)) {
                    long submitTime = events.get(taskKey).timestamp;
                    out.collect(
                            new Tuple3<>(
                                    taskEvent.jobId,
                                    taskEvent.taskIndex,
                                    taskEvent.timestamp - submitTime));
                    // clean-up state
                    events.remove(taskKey);
                } else {
                    // this is an unmatched FINISH event => store
                    events.put(taskKey, taskEvent);
                }
            }
        }
    }

    private static class LatencySink<T> extends StreamSink<T> {
        private final Logger logger;

        public LatencySink(Logger logger) {
            super(
                    new SinkFunction<T>() {
                        @Override
                        public void invoke(Object value, Context ctx) {}
                    });
            this.logger = logger;
        }

        @Override
        public void processLatencyMarker(LatencyMarker latencyMarker) throws Exception {
            logger.info(
                    "%{}%{}",
                    "current_latency", System.currentTimeMillis() - latencyMarker.getMarkedTime());
        }
    }
}
