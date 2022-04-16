package org.apache.flink.streaming.examples.clusterdata.kafkajob;

import org.apache.flink.streaming.examples.clusterdata.datatypes.EventType;
import org.apache.flink.streaming.examples.clusterdata.datatypes.TaskEvent;
import org.apache.flink.streaming.examples.clusterdata.utils.AppBase;
import org.apache.flink.streaming.examples.clusterdata.utils.TaskEventSchema;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer;
import org.apache.flink.util.Collector;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

/**
 * Other ideas:
 * - average task runtime per priority
 * - a histogram of task scheduling latency
 */
public class MaxTaskCompletionTimeFromKafka extends AppBase {

    private static final String LOCAL_ZOOKEEPER_HOST = "localhost:2181";
    private static final String LOCAL_KAFKA_BROKER = "localhost:9092";
    private static final String TASKS_GROUP = "taskGroup";

    public static void main(String[] args) throws Exception {

        // set up streaming execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.getConfig().setAutoWatermarkInterval(1000);

        env.setParallelism(2);

        // configure the Kafka consumer
        Properties kafkaProps = new Properties();
        kafkaProps.setProperty("zookeeper.connect", LOCAL_ZOOKEEPER_HOST);
        kafkaProps.setProperty("bootstrap.servers", LOCAL_KAFKA_BROKER);
        kafkaProps.setProperty("group.id", TASKS_GROUP);
        // always read the Kafka topic from the start
        kafkaProps.setProperty("auto.offset.reset", "earliest");

        // create a Kafka consumer
//        FlinkKafkaConsumer<TaskEvent> consumer = new FlinkKafkaConsumer<>(
//                "filteredTasks",
//                new TaskEventSchema(),
//                kafkaProps);
        KafkaSource<TaskEvent> source =
                KafkaSource.<TaskEvent>builder()
                        .setBootstrapServers(LOCAL_KAFKA_BROKER)
                        .setGroupId(TASKS_GROUP)
                        .setTopics("wiki-edits")
                        .setDeserializer(
                                KafkaRecordDeserializationSchema.valueOnly(new TaskEventSchema()))
                        .setStartingOffsets(OffsetsInitializer.latest())
                        .build();

        DataStream<TaskEvent> events =
                env.fromSource(
                        source, WatermarkStrategy.noWatermarks(), "StateMachineExampleSource");

        // assign a timestamp extractor to the consumer
//        consumer.assignTimestampsAndWatermarks(WatermarkStrategy
//                .<TaskEvent>forMonotonousTimestamps()
//                .withTimestampAssigner((taskEvent, l) -> taskEvent.timestamp));

        // create the data stream
//        DataStream<TaskEvent> events = env.addSource(taskSourceOrTest(consumer));

        // get SUBMIT and FINISH events in one place "jobId", "taskIndex"
        DataStream<Tuple2<Integer, Long>> taskDurations = events
                .keyBy(new KeySelector<TaskEvent, Tuple2<Integer, Long>>(){
                    @Override
                    public Tuple2<Integer, Long> getKey(TaskEvent taskEvent) throws Exception {
                        return Tuple2.of(taskEvent.taskIndex, taskEvent.jobId);
                    }
                })
                .flatMap(new CalculateTaskDuration());

        DataStream<Tuple2<Integer, Long>> maxDurationsPerPriority = taskDurations
                // key by priority
                .keyBy(jobEvents -> jobEvents.getField(0))
                .flatMap(new MaxDurationPerPriority());

        printOrTest(maxDurationsPerPriority);

        env.execute();
    }

    private static final class MaxDurationPerPriority implements FlatMapFunction<Tuple2<Integer, Long>, Tuple2<Integer, Long>> {

        Map<Integer, Long> currentMaxMap = new HashMap<>();
        LinkedList<Long> currentMaxList = new LinkedList<>();
        int maxListLength = 1000;
        long tempMax = 0;
        Random r = new Random();

        private Long findMax(List<Long> l){
            Long max = l.get(0);
            for (Long aLong : l) {
                if(aLong > max){
                    max = aLong;
                }
            }
            return max;
        }

        @Override
        public void flatMap(Tuple2<Integer, Long> t, Collector<Tuple2<Integer, Long>> out) throws Exception {
            long generatedLong = r.nextLong();
            currentMaxList.add(t.f1 + generatedLong);
            if (currentMaxList.size() > maxListLength){
                currentMaxList.pop();
            }
            tempMax = findMax(currentMaxList);
            //System.out.println(tempMax);
            if (currentMaxMap.containsKey(t.f0)) {
                // find the maximum
                long currentMax = currentMaxMap.get(t.f0);
                if (currentMax < t.f1) {
                    // update map and output
                    currentMaxMap.put(t.f0, t.f1);
                    out.collect(t);
                }
            } else {
                // first time we come across this priority
                currentMaxMap.put(t.f0, t.f1);
                out.collect(t);
            }

        }
    }

    private static final class CalculateTaskDuration implements FlatMapFunction<TaskEvent, Tuple2<Integer, Long>> {

        // a map of "task => event"
        Map<Tuple2<Long, Integer>, TaskEvent> events = new HashMap<>();

        @Override
        public void flatMap(TaskEvent taskEvent, Collector<Tuple2<Integer, Long>> out) throws Exception {

            Tuple2<Long, Integer> taskKey = new Tuple2<>(taskEvent.jobId, taskEvent.taskIndex);

            // what's the event type?
            if (taskEvent.eventType.equals(EventType.SUBMIT)) {
                // check if there is already a SUBMIT for this task and if yes, update it
                if (events.containsKey(taskKey)) {
                    TaskEvent stored = events.get(taskKey);
                    if (stored.eventType.equals(EventType.SUBMIT)) {
                        // keep latest SUBMIT
                        if (stored.timestamp < taskEvent.timestamp) {
                            events.put(taskKey, taskEvent);
                        }
                    } else {
                        // stored event is a FINISH => output the duration
                        out.collect(new Tuple2<>(taskEvent.priority, stored.timestamp - taskEvent.timestamp));
                        // clean-up state
                        events.remove(taskKey);
                    }
                } else {
                    // first SUBMIT we see for this task
                    events.put(taskKey, taskEvent);
                }
            } else {
                // this is a FINISH event: compute duration and emit downstream
                if (events.containsKey(taskKey)) {
                    long submitTime = events.get(taskKey).timestamp;
                    out.collect(new Tuple2<>(taskEvent.priority, taskEvent.timestamp - submitTime));
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
