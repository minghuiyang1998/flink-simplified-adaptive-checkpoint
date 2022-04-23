package org.apache.flink.streaming.examples.clusterdata.kafkajob;

import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.examples.clusterdata.datatypes.TaskEvent;
import org.apache.flink.streaming.examples.clusterdata.sources.TaskEventSource;
import org.apache.flink.streaming.examples.clusterdata.utils.AppBase;
import org.apache.flink.streaming.examples.clusterdata.utils.TaskEventSchema;

import java.util.Properties;

/**
 * Write SUBMIT(0) and FINISH(4) TaskEvents to a Kafka topic.
 *
 * <p>Parameters: --input path-to-input-file
 */
public class FilterTaskEventsToKafka extends AppBase {

    private static final String LOCAL_KAFKA_BROKER = "localhost:9092";
    private static final String REMOTE_KAFKA_BROKER = "20.127.226.8:9092";
    public static final String FILTERED_TASKS_TOPIC = "wiki-edits";

    public static void main(String[] args) throws Exception {

        // ParameterTool params = ParameterTool.fromArgs(args);
        // String input = params.get("input", pathToTaskEventData);
        String input = PATH_TO_TASK_EVENT_DATA;
        Properties properties = new Properties();
        properties.setProperty("bootstrap.servers", ":9092");

        final int servingSpeedFactor = 600; // events of 10 minutes are served in 1 second

        // set up streaming execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        env.setParallelism(3);

        // start the data generator
        DataStream<TaskEvent> taskEvents =
                env.addSource(taskSourceOrTest(new TaskEventSource(input, servingSpeedFactor)))
                        .setParallelism(1);

        DataStream<TaskEvent> filteredEvents =
                taskEvents
                        // filter out task events that do not correspond to SUBMIT or FINISH
                        // transitions
                        .filter(new TaskFilter());

        printOrTest(filteredEvents);

        // write the filtered data to a Kafka sink
        KafkaSink<TaskEvent> sink =
                KafkaSink.<TaskEvent>builder()
                        .setBootstrapServers(REMOTE_KAFKA_BROKER)
                        .setDeliverGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
                        .setRecordSerializer(
                                KafkaRecordSerializationSchema.builder()
                                        .setTopic(FILTERED_TASKS_TOPIC)
                                        .setValueSerializationSchema(new TaskEventSchema())
                                        .build())
                        .build();

        filteredEvents.sinkTo(sink);
        // run the cleansing pipeline
        env.execute("Task Events to Kafka");
    }

    private static final class TaskFilter implements FilterFunction<TaskEvent> {

        @Override
        public boolean filter(TaskEvent taskEvent) throws Exception {
            // return taskEvent.eventType.equals(EventType.SUBMIT)
            //        || taskEvent.eventType.equals(EventType.FINISH);
            return true;
        }
    }
}
