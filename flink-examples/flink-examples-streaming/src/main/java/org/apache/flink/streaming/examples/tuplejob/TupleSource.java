package org.apache.flink.streaming.examples.tuplejob;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;


public class TupleSource implements SourceFunction<Tuple2<String, Integer>> {
    public volatile boolean isRunning = true;
    private static final Logger LOG = LoggerFactory.getLogger(TupleSource.class);

    private final String[] keyCollection = new String[]{"A", "B", "C", "D", "E"};

    @Override
    public void run(SourceContext<Tuple2<String, Integer>> ctx) throws Exception {
        Random randomTime = new Random();
        Random randomStr = new Random();
        Random randomInt = new Random();
        while(isRunning) {
            Thread.sleep(randomTime.nextInt(5));
            String key = keyCollection[randomStr.nextInt(5)];
            int value = randomInt.nextInt(10);
            ctx.collect(new Tuple2<>(key, value));
        }
    }

    @Override
    public void cancel() {
        isRunning = false;
    }
}
