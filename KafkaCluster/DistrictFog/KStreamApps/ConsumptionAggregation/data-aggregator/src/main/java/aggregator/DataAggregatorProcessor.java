package aggregator;

import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueIterator;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

public class DataAggregatorProcessor implements Processor<String, HouseholdConsumption, String, Double> {

    private ProcessorContext<String, Double> context;
    private CustomKeyValueStore<Long, Double> hourlyConsumptionStore;
    private final String storeName;
    private final Duration windowSize = Duration.ofHours(1);
    // Grace period for late arriving data, e.g. 5 minutes. Adjust as needed.
    private final Duration gracePeriod = Duration.ofMinutes(5); 

    public DataAggregatorProcessor(String storeName) {
        this.storeName = storeName;
    }

    @Override
    public void init(ProcessorContext<String, Double> context) {
        this.context = context;
        this.hourlyConsumptionStore = context.getStateStore(storeName);
        if (this.hourlyConsumptionStore == null) {
            throw new IllegalStateException("State store '" + storeName + "' not found. Check topology.");
        }

        // Schedule punctuation based on stream time (event time)
        // Punctuate, for example, every 1 minute to check for closed windows
        this.context.schedule(Duration.ofMinutes(1), PunctuationType.STREAM_TIME, this::punctuateHourly);
        System.out.println("DataAggregatorProcessor initialized with store: " + storeName + " and scheduled punctuation.");
    }

    @Override
    public void process(Record<String, HouseholdConsumption> record) {
        HouseholdConsumption data = record.value();
        if (data == null || data.getTimestamp() == null) {
            System.err.println("Skipping null data or data with null timestamp: " + record.key());
            return;
        }

        try {
            OffsetDateTime recordTimestamp = OffsetDateTime.parse(data.getTimestamp());
            long windowStartEpochMillis = recordTimestamp.truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli();

            Double currentSum = hourlyConsumptionStore.get(windowStartEpochMillis);
            if (currentSum == null) {
                currentSum = 0.0;
            }
            double newSum = currentSum + data.getConsumption();
            hourlyConsumptionStore.put(windowStartEpochMillis, newSum);

            // Optional: Log processing
            // System.out.println("Processed record key=" + record.key() +
            //                    ", timestamp=" + data.getTimestamp() +
            //                    ", consumption=" + data.getConsumption() +
            //                    ", windowStart=" + Instant.ofEpochMilli(windowStartEpochMillis) +
            //                    ", newSum=" + newSum);

        } catch (Exception e) {
            System.err.println("Error processing record: " + record.key() + ", value: " + data + ". Error: " + e.getMessage());
            // Optionally forward the original record to an error topic or re-throw
        }
    }

    private void punctuateHourly(long currentStreamTimestamp) {
        // currentStreamTimestamp is the current watermark based on event times
        // System.out.println("Punctuate called with stream time: " + Instant.ofEpochMilli(currentStreamTimestamp));

        try (KeyValueIterator<Long, Double> iterator = hourlyConsumptionStore.all()) {
            Map<Long, Double> windowsToForward = new HashMap<>();

            while (iterator.hasNext()) {
                KeyValue<Long, Double> kv = iterator.next();
                long windowStartMillis = kv.key;
                double sum = kv.value;

                // A window is considered closed if the current stream time (watermark)
                // has passed the end of the window plus the grace period.
                long windowEndMillis = windowStartMillis + windowSize.toMillis();
                if (currentStreamTimestamp > windowEndMillis + gracePeriod.toMillis()) {
                    // This window is closed and past grace period
                    windowsToForward.put(windowStartMillis, sum);
                }
            }
            
            // Forward records and clean up store outside the iterator loop to avoid ConcurrentModificationException
            // if the store's iterator doesn't support modification during iteration.
            // Our custom store's iterator iterates over a snapshot, so it's safer.
            for(Map.Entry<Long, Double> entry : windowsToForward.entrySet()){
                long windowStartMillis = entry.getKey();
                double sum = entry.getValue();

                String outputKey = "ward1_" + Instant.ofEpochMilli(windowStartMillis).toString(); // Example key format
                Record<String, Double> aggregatedRecord = new Record<>(outputKey, sum, currentStreamTimestamp);
                context.forward(aggregatedRecord);

                System.out.println("Forwarded aggregated data for window " +
                                   Instant.ofEpochMilli(windowStartMillis).toString() +
                                   ": sum=" + sum + " at stream time " + Instant.ofEpochMilli(currentStreamTimestamp));

                // Remove the processed window from the store
                hourlyConsumptionStore.delete(windowStartMillis);
            }

        } catch (Exception e) {
            System.err.println("Error during punctuation: " + e.getMessage());
            e.printStackTrace(); // Log stack trace for debugging
        }
    }

    @Override
    public void close() {
        System.out.println("DataAggregatorProcessor closed.");
        // Resources managed by Kafka Streams (like stores) are closed automatically.
    }
}