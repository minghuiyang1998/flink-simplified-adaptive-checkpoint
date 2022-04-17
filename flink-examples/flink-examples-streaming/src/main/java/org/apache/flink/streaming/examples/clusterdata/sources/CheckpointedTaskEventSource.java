package org.apache.flink.streaming.examples.clusterdata.sources;

import org.apache.flink.streaming.api.checkpoint.ListCheckpointed;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.examples.clusterdata.datatypes.TaskEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * This SourceFunction generates a data stream of TaskEvent records which are read from a gzipped
 * input file. Each record has a time stamp and the input file must be ordered by this time stamp.
 *
 * <p>In order to simulate a realistic stream source, the SourceFunction serves events proportional
 * to their timestamps. In addition, the serving of events can be delayed by a bounded random delay
 * which causes the events to be served slightly out-of-order of their timestamps.
 *
 * <p>The serving speed of the SourceFunction can be adjusted by a serving speed factor. A factor of
 * 60.0 increases the logical serving time by a factor of 60, i.e., events of one minute (60
 * seconds) are served in 1 second.
 *
 * <p>This SourceFunction is an EventSourceFunction and does continuously emit watermarks. Hence it
 * is able to operate in event time mode which is configured as follows:
 *
 * <p>StreamExecutionEnvironment.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
 */
public class CheckpointedTaskEventSource
        implements SourceFunction<TaskEvent>, ListCheckpointed<Long> {

    private final String dataFilePath;
    private final int servingSpeed;

    private transient BufferedReader reader;
    private transient InputStream gzipStream;

    // state
    // number of emitted events
    private long eventCnt = 0;

    /**
     * Serves the TaskEvent records from the specified and ordered gzipped input file. Events are
     * served out-of time stamp order with specified maximum random delay in a serving speed which
     * is proportional to the specified serving speed factor.
     *
     * @param dataFilePath The gzipped input file from which the TaskEvent records are read.
     * @param servingSpeedFactor The serving speed factor by which the logical serving time is
     *     adjusted.
     */
    public CheckpointedTaskEventSource(String dataFilePath, int servingSpeedFactor) {
        this.dataFilePath = dataFilePath;
        this.servingSpeed = servingSpeedFactor;
    }

    @Override
    public void run(SourceContext<TaskEvent> sourceContext) throws Exception {

        File directory = new File(dataFilePath);
        File[] files = directory.listFiles();
        Arrays.sort(files);

        final Object lock = sourceContext.getCheckpointLock();
        Long prevTaskTime = null;
        String line;
        int count = 0;

        for (File file : files) {

            FileInputStream fis = new FileInputStream(file);
            gzipStream = new GZIPInputStream(fis);
            reader = new BufferedReader(new InputStreamReader(gzipStream, "UTF-8"));

            // skip emitted events
            while (count < eventCnt && reader.ready() && (line = reader.readLine()) != null) {
                count++;
                TaskEvent task = TaskEvent.fromString(line);
                prevTaskTime = task.timestamp / 1000;
            }

            // emit all subsequent events proportial to their timestamp
            while (reader.ready() && (line = reader.readLine()) != null) {

                TaskEvent task = TaskEvent.fromString(line);
                long taskTime = task.timestamp / 1000;

                if (prevTaskTime != null) {
                    long diff = (taskTime - prevTaskTime) / servingSpeed;
                    Thread.sleep(diff);
                }

                synchronized (lock) {
                    eventCnt++;
                    sourceContext.collectWithTimestamp(task, taskTime);
                    sourceContext.emitWatermark(new Watermark(taskTime - 1));
                }

                prevTaskTime = taskTime;
            }
            this.reader.close();
            this.reader = null;
            this.gzipStream.close();
            this.gzipStream = null;
        }
    }

    @Override
    public void cancel() {
        try {
            if (this.reader != null) {
                this.reader.close();
            }
            if (this.gzipStream != null) {
                this.gzipStream.close();
            }
        } catch (IOException ioe) {
            throw new RuntimeException("Could not cancel SourceFunction", ioe);
        } finally {
            this.reader = null;
            this.gzipStream = null;
        }
    }

    @Override
    public List<Long> snapshotState(long checkpointId, long timestamp) throws Exception {
        return Collections.singletonList(eventCnt);
    }

    @Override
    public void restoreState(List<Long> state) throws Exception {
        for (Long s : state) {
            this.eventCnt = s;
        }
    }
}
