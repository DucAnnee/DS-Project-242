package aggregator;

import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.processor.api.RecordMetadata;
import org.apache.kafka.streams.state.KeyValueIterator;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

public class DistrictMinuteSumProcessor implements Processor<String, HouseholdConsumption, String, Double> {

    private ProcessorContext<String, Double> context;
    private CustomKeyValueStore<Long, Double> minuteDistrictSumStore;
    private final String storeName;
    private final Duration windowSize = Duration.ofMinutes(1); // Window is 1 minute
    // Grace period for late arriving data. For 1-minute windows, a shorter grace period might be suitable.
    private final Duration gracePeriod = Duration.ofMinutes(1);

    public DistrictMinuteSumProcessor(String storeName) {
        this.storeName = storeName;
    }

    @Override
    public void init(ProcessorContext<String, Double> context) {
        this.context = context;
        this.minuteDistrictSumStore = context.getStateStore(storeName);
        if (this.minuteDistrictSumStore == null) {
            throw new IllegalStateException("State store '" + storeName + "' not found. Check topology.");
        }

        // Punctuate, for example, every 30 seconds to check for closed 1-minute windows
        this.context.schedule(Duration.ofSeconds(30), PunctuationType.STREAM_TIME, this::punctuateMinuteSum);
        System.out.println("DistrictMinuteSumProcessor initialized with store: " + storeName + " and scheduled punctuation.");
    }

    @Override
    public void process(Record<String, HouseholdConsumption> record) {
        HouseholdConsumption data = record.value();
        String topicName = this.context.recordMetadata().map(RecordMetadata::topic).orElse("N/A");

        if (data == null || data.getTimestamp() == null) {
            System.err.println("Skipping null data or data with null timestamp: key=" + record.key() + ", topic=" + topicName);
            return;
        }

        try {
            OffsetDateTime recordTimestamp = OffsetDateTime.parse(data.getTimestamp());
            // Truncate to the beginning of the minute for windowing
            long windowStartEpochMillis = recordTimestamp.truncatedTo(ChronoUnit.MINUTES).toInstant().toEpochMilli();
            double currentConsumption = data.getConsumption();

            Double currentSumInWindow = minuteDistrictSumStore.get(windowStartEpochMillis);
            if (currentSumInWindow == null) {
                currentSumInWindow = 0.0;
            }

            double newSumInWindow = currentSumInWindow + currentConsumption;
            minuteDistrictSumStore.put(windowStartEpochMillis, newSumInWindow);

        } catch (Exception e) {
            System.err.println("Error processing record for minute sum: key=" + record.key() + ", topic=" + topicName + ", value=" + data + ". Error: " + e.getMessage());
        }
    }

    private void punctuateMinuteSum(long currentStreamTimestamp) {
        try (KeyValueIterator<Long, Double> iterator = minuteDistrictSumStore.all()) {
            Map<Long, Double> windowsToForward = new HashMap<>();

            while (iterator.hasNext()) {
                KeyValue<Long, Double> kv = iterator.next();
                long windowStartMillis = kv.key;
                double sumConsumption = kv.value;

                long windowEndMillis = windowStartMillis + windowSize.toMillis();
                if (currentStreamTimestamp > windowEndMillis + gracePeriod.toMillis()) {
                    windowsToForward.put(windowStartMillis, sumConsumption);
                }
            }

            for(Map.Entry<Long, Double> entry : windowsToForward.entrySet()){
                long windowStartMillis = entry.getKey();
                double sumValue = entry.getValue();

                String outputKey = "district1_sum_1min_" + Instant.ofEpochMilli(windowStartMillis).toString();
                Record<String, Double> sumRecord = new Record<>(outputKey, sumValue, currentStreamTimestamp);
                context.forward(sumRecord);

                System.out.println("Forwarded district 1-minute sum for window " +
                                   Instant.ofEpochMilli(windowStartMillis).toString() +
                                   ": sum_consumption=" + sumValue + " at stream time " + Instant.ofEpochMilli(currentStreamTimestamp));

                minuteDistrictSumStore.delete(windowStartMillis);
            }

        } catch (UnsupportedOperationException uoe) {
             System.err.println("Warning during district minute sum punctuation: peekNextKey() is not implemented: " + uoe.getMessage());
        } catch (Exception e) {
            System.err.println("Error during district minute sum punctuation: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void close() {
        System.out.println("DistrictMinuteSumProcessor closed.");
    }
}