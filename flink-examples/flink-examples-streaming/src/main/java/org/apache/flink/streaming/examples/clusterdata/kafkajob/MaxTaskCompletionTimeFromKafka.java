package org.apache.flink.streaming.examples.clusterdata.kafkajob;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
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
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.KafkaSourceOptions;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.runtime.state.hashmap.HashMapStateBackend;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.examples.clusterdata.datatypes.EventType;
import org.apache.flink.streaming.examples.clusterdata.datatypes.TaskEvent;
import org.apache.flink.streaming.examples.clusterdata.utils.AppBase;
import org.apache.flink.streaming.examples.clusterdata.utils.TaskEventSchema;
import org.apache.flink.util.Collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.flink.streaming.api.CheckpointingMode.EXACTLY_ONCE;

/** Other ideas: - average task runtime per priority - a histogram of task scheduling latency. */
public class MaxTaskCompletionTimeFromKafka extends AppBase {
    private static final String LOCAL_KAFKA_BROKER = "localhost:9092";
    private static final String REMOTE_KAFKA_BROKER = "20.127.226.8:9092";
    private static final String TASKS_GROUP = "task_group_1";
    public static final String TASKS_TOPIC = "wiki-edits";
    public static final String CHECKPOINT_DIR = "file:///home/CS551Team2/Checkpoint";

    public static void main(String[] args) throws Exception {
        // set up streaming execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // set up checkpointing
        env.enableCheckpointing(20000L, EXACTLY_ONCE);
        env.setStateBackend(new HashMapStateBackend());
        env.getCheckpointConfig().setCheckpointStorage(CHECKPOINT_DIR);

        // enable Adapter
        env.enableCheckpointAdapter(30000L);
        env.setCheckpointAdapterMetricInterval(10000L);
        env.setCheckpointAdapterAllowRange(0.3);

        KafkaSource<TaskEvent> source =
                KafkaSource.<TaskEvent>builder()
                        .setBootstrapServers(REMOTE_KAFKA_BROKER)
                        .setGroupId(TASKS_GROUP)
                        .setTopics(TASKS_TOPIC)
                        .setDeserializer(
                                KafkaRecordDeserializationSchema.valueOnly(new TaskEventSchema()))
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
        DataStream<Tuple3<TaskEvent, Long, Long>> taskDurations =
                events.map(new AddProcessTime())
                        .keyBy(
                                new KeySelector<Tuple2<TaskEvent, Long>, Tuple2<Integer, Long>>() {
                                    @Override
                                    public Tuple2<Integer, Long> getKey(
                                            Tuple2<TaskEvent, Long> tuple) throws Exception {
                                        TaskEvent taskEvent = tuple.f0;
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
                                        Tuple3<TaskEvent, Long, Long>, Tuple2<Integer, Long>>() {
                                    @Override
                                    public Tuple2<Integer, Long> getKey(
                                            Tuple3<TaskEvent, Long, Long> tuple3) throws Exception {
                                        TaskEvent taskEvent = tuple3.f0;
                                        return Tuple2.of(taskEvent.taskIndex, taskEvent.jobId);
                                    }
                                })
                        .flatMap(new TaskWithMinDurationPerJob())
                        .map(new CalcLatency());
        // disableChaining();

        printOrTest(maxDurationsPerJob);

        env.execute();
    }

    private static final class CalcLatency
            implements MapFunction<Tuple2<TaskEvent, Long>, Tuple2<Long, Long>> {
        private long count = 0;
        private long sum = 0;
        private int threshold = 100;
        private static final Logger lg = LoggerFactory.getLogger(CalcLatency.class);

        @Override
        public Tuple2<Long, Long> map(Tuple2<TaskEvent, Long> tuple2) throws Exception {
            Long currentTime = System.currentTimeMillis();
            Long eventStartTime = tuple2.f1;
            Long latency = currentTime - eventStartTime;
            count += 1;
            sum += latency;
            if (count != 0 && count % threshold == 0) {
                long aver = sum / count;
                lg.info("average latency for " + threshold + " : " + aver);
                count = 0;
                sum = 0;
            }
            return Tuple2.of(tuple2.f0.jobId, tuple2.f1);
        }
    }

    private static final class AddProcessTime
            implements MapFunction<TaskEvent, Tuple2<TaskEvent, Long>> {

        @Override
        public Tuple2<TaskEvent, Long> map(TaskEvent value) throws Exception {
            // try {
            //   Thread.sleep(1);
            // } catch (Exception e) {
            //   e.printStackTrace();
            // }
            Long timestamp = System.currentTimeMillis();
            return new Tuple2<>(value, timestamp);
        }
    }

    private static final class TaskWithMinDurationPerJob
            extends RichFlatMapFunction<Tuple3<TaskEvent, Long, Long>, Tuple2<TaskEvent, Long>> {

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
        public void flatMap(
                Tuple3<TaskEvent, Long, Long> tuple3, Collector<Tuple2<TaskEvent, Long>> out)
                throws Exception {
            // <job, maxTask>
            Integer count = countCompletedTasks.value();
            if (count == null) {
                countCompletedTasks.update(1);
            } else {
                countCompletedTasks.update(count + 1);
            }
            TaskEvent task = tuple3.f0;
            Long currJobId = task.jobId;
            Long duration = tuple3.f2;
            Long minInJob = minDurationPerJob.get(currJobId);
            if (minInJob == null) {
                minInJob = duration;
            } else {
                minInJob = Math.min(minInJob, duration);
            }
            minDurationPerJob.put(currJobId, minInJob);
            out.collect(new Tuple2<>(task, tuple3.f1));
        }
    }

    private static final class CalculateTaskDuration
            extends RichFlatMapFunction<Tuple2<TaskEvent, Long>, Tuple3<TaskEvent, Long, Long>> {

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
        public void flatMap(
                Tuple2<TaskEvent, Long> tuple2, Collector<Tuple3<TaskEvent, Long, Long>> out)
                throws Exception {
            TaskEvent taskEvent = tuple2.f0;
            Tuple2<Long, Integer> taskKey = new Tuple2<>(taskEvent.jobId, taskEvent.taskIndex);
            // Log.info("jobID: " + taskEvent.jobId + " taskIndex: " + taskEvent.taskIndex);
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
                                        tuple2.f0,
                                        tuple2.f1,
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
                            new Tuple3<>(tuple2.f0, tuple2.f1, taskEvent.timestamp - submitTime));
                    events.remove(taskKey);
                } else {
                    // this is an unmatched FINISH event => store
                    events.put(taskKey, taskEvent);
                }
            }
        }
    }
}
