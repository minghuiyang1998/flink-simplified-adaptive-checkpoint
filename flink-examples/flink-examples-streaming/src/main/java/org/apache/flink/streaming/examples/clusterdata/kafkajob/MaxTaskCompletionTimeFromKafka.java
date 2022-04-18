package org.apache.flink.streaming.examples.clusterdata.kafkajob;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.KafkaSourceOptions;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.examples.clusterdata.datatypes.EventType;
import org.apache.flink.streaming.examples.clusterdata.datatypes.TaskEvent;
import org.apache.flink.streaming.examples.clusterdata.utils.AppBase;
import org.apache.flink.streaming.examples.clusterdata.utils.TaskEventSchema;
import org.apache.flink.util.Collector;

import org.apache.kafka.clients.consumer.ConsumerConfig;

import static org.apache.flink.streaming.api.CheckpointingMode.EXACTLY_ONCE;

/** Other ideas: - average task runtime per priority - a histogram of task scheduling latency. */
public class MaxTaskCompletionTimeFromKafka extends AppBase {
    private static final String LOCAL_KAFKA_BROKER = "localhost:9092";
    private static final String TASKS_GROUP = "taskGroup";

    public static void main(String[] args) throws Exception {
        // set up streaming execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(2);

        // set up checkpointing
        env.enableCheckpointing(5000L, EXACTLY_ONCE);
        // TODO: statebackend here

        KafkaSource<TaskEvent> source =
                KafkaSource.<TaskEvent>builder()
                        .setBootstrapServers(LOCAL_KAFKA_BROKER)
                        .setGroupId(TASKS_GROUP)
                        .setTopics("wiki-edits")
                        .setDeserializer(
                                KafkaRecordDeserializationSchema.valueOnly(new TaskEventSchema()))
                        // TODO: adjust speed by control poll records
                        .setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500")
                        .setProperty(KafkaSourceOptions.REGISTER_KAFKA_CONSUMER_METRICS.key(), "true")
                        // recover from checkpoint
                        .setProperty(KafkaSourceOptions.COMMIT_OFFSETS_ON_CHECKPOINT.key(), "true")
                        // If each partition has a committed offset, the offset will be consumed from the committed offset.
                        // Start consuming from scratch when there is no submitted offset
                        .setStartingOffsets(OffsetsInitializer.earliest())
                        .build();
        DataStream<TaskEvent> events = env.fromSource(source, WatermarkStrategy.noWatermarks(), "Kafka source");

        // get SUBMIT and FINISH events in one place "jobId", "taskIndex", out put task duration
        DataStream<Tuple2<String, Long>> taskDurations =
                events.keyBy(
                        new KeySelector<TaskEvent, Tuple2<Integer, Long>>() {
                            @Override
                            public Tuple2<Integer, Long> getKey(TaskEvent taskEvent)
                                    throws Exception {
                                return Tuple2.of(taskEvent.taskIndex, taskEvent.jobId);
                            }
                        })
                .flatMap(new CalculateTaskDuration());

        DataStream<Tuple2<String, Long>> maxDurationsPerJob =
                taskDurations
                        // key by priority
                        .keyBy(taskEvent -> taskEvent.getField(0))
                        .flatMap(new TaskWithMinDurationPerJob());

        printOrTest(maxDurationsPerJob);

        env.execute();
    }

    private static final class TaskWithMinDurationPerJob
            extends RichFlatMapFunction<Tuple2<String, Long>, Tuple2<String, Long>> {

        private ListState<Tuple2<String, Long>> tasks;
        private MapState<String, String> minDurationPerJob;

        @Override
        public void open(Configuration parameters) throws Exception {
            tasks = getRuntimeContext().getListState(new ListStateDescriptor<>(
                    "tasks-list",
                    TypeInformation.of(new TypeHint<Tuple2<String, Long>>() {})
            ));
            minDurationPerJob = getRuntimeContext().getMapState(new MapStateDescriptor<String, String>("max-map", String.class, String.class));
        }

        @Override
        public void flatMap(Tuple2<String, Long> taskKey, Collector<Tuple2<String, Long>> out)
                throws Exception {
            // <job, maxTask>
            String[] curr_ids = taskKey.f0.split(" ");
            String curr_jobId = curr_ids[0];
            tasks.add(taskKey);

            // return task index with min and store in mapstate and listState
            Long minInJob = Long.MAX_VALUE;
            String minTaskId = "";
            for (Tuple2<String, Long> task : tasks.get()) {
                String[] ids = task.f0.split(" ");
                String jobId = ids[0];
                String taskIndex = ids[1];
                Long duration = task.f1;
                if (jobId.equals(curr_jobId)) {
                    if (duration < minInJob) {
                        minInJob = duration;
                        minTaskId = taskIndex;
                    }
                }
            }
            minDurationPerJob.put(curr_jobId, minTaskId);
            out.collect(new Tuple2<>(curr_jobId, minInJob));
        }
    }

    private static final class CalculateTaskDuration
            extends RichFlatMapFunction<TaskEvent, Tuple2<String, Long>> {

        // a map of "task => event"
        // keep all values in state
        private MapState<String, TaskEvent> events;

        @Override
        public void open(Configuration parameters) throws Exception {
            events = getRuntimeContext().getMapState(new MapStateDescriptor<>("duration-map", String.class, TaskEvent.class));
        }

        @Override
        public void flatMap(TaskEvent taskEvent, Collector<Tuple2<String, Long>> out)
                throws Exception {

            String taskKey = taskEvent.jobId + " " + taskEvent.taskIndex;

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
                                new Tuple2<>(
                                        taskKey,
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
                    out.collect(new Tuple2<>(taskKey, taskEvent.timestamp - submitTime));
                    // clean-up state
                    events.remove(taskKey);
                } else {
                    // this is an unmatched FINISH event => store
                    events.put(taskKey, taskEvent);
                }
            }
        }
    }
}
