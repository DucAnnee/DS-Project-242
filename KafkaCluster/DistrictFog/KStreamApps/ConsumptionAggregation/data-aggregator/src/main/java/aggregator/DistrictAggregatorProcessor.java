package aggregator;

import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.processor.api.RecordMetadata; // Import this
import org.apache.kafka.streams.state.KeyValueIterator;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional; // Import this

// Processor now handles <String (input key), Double (input value), String (output key), Double (output value)>
public class DistrictAggregatorProcessor implements Processor<String, Double, String, Double> {

    // Context type changes to reflect new input value type
    private ProcessorContext<String, Double> context;
    private CustomKeyValueStore<Long, Double> hourlyDistrictSumStore; // Renamed for clarity
    private final String storeName;
    private final Duration windowSize = Duration.ofHours(1);
    private final Duration gracePeriod = Duration.ofMinutes(5);

    public DistrictAggregatorProcessor(String storeName) {
        this.storeName = storeName;
    }

    @Override
    public void init(ProcessorContext<String, Double> context) { // Context type updated
        this.context = context;
        this.hourlyDistrictSumStore = context.getStateStore(storeName);
        if (this.hourlyDistrictSumStore == null) {
            throw new IllegalStateException("State store '" + storeName + "' not found. Check topology.");
        }

        this.context.schedule(Duration.ofMinutes(1), PunctuationType.STREAM_TIME, this::punctuateHourlyDistrictSum);
        System.out.println("DistrictAggregatorProcessor (from Ward Sums) initialized with store: " + storeName + " and scheduled punctuation.");
    }

    @Override
    public void process(Record<String, Double> record) { // Record type updated
        String inputKey = record.key(); // e.g., "ward1_2025-05-29T08:00:00Z"
        Double wardHourlySum = record.value();

        // Get topic name from context's record metadata
        String topicName = this.context.recordMetadata()
                                     .map(RecordMetadata::topic)
                                     .orElse("N/A");

        if (wardHourlySum == null) {
            // Corrected line for error around original line 51
            System.err.println("Skipping null ward hourly sum for record key: " + inputKey + " from topic: " + topicName);
            return;
        }

        // Extract timestamp from the key to determine the window for the district sum
        long windowStartEpochMillis = -1;
        int lastUnderscoreIndex = inputKey.lastIndexOf('_');
        if (lastUnderscoreIndex != -1 && lastUnderscoreIndex < inputKey.length() - 1) {
            String timestampString = inputKey.substring(lastUnderscoreIndex + 1);
            try {
                Instant instant = Instant.parse(timestampString);
                // The timestamp from the key is already the start of the hour window
                windowStartEpochMillis = instant.toEpochMilli();
            } catch (DateTimeParseException e) {
                System.err.println("Error parsing timestamp from input key '" + inputKey + "' in processor: " + e.getMessage());
                return; // Skip if we can't determine the window
            }
        } else {
            System.err.println("Unexpected input key format in processor: " + inputKey);
            return; // Skip if key format is wrong
        }

        if (windowStartEpochMillis == -1) {
             System.err.println("Could not determine window start epoch millis for key: " + inputKey);
             return;
        }

        try {
            Double currentDistrictSum = hourlyDistrictSumStore.get(windowStartEpochMillis);
            if (currentDistrictSum == null) {
                currentDistrictSum = 0.0;
            }
            double newDistrictSum = currentDistrictSum + wardHourlySum;
            hourlyDistrictSumStore.put(windowStartEpochMillis, newDistrictSum);

        } catch (Exception e) {
            // Corrected line for error around original line 87
            System.err.println("Error processing district aggregation for key: " + inputKey + ", topic: " + topicName + ", value: " + wardHourlySum + ". Error: " + e.getMessage());
        }
    }

    private void punctuateHourlyDistrictSum(long currentStreamTimestamp) {
        try (KeyValueIterator<Long, Double> iterator = hourlyDistrictSumStore.all()) {
            Map<Long, Double> windowsToForward = new HashMap<>();

            while (iterator.hasNext()) {
                KeyValue<Long, Double> kv = iterator.next();
                long windowStartMillis = kv.key; // This is the key from the store (epoch millis)
                double districtSum = kv.value;

                long windowEndMillis = windowStartMillis + windowSize.toMillis();
                if (currentStreamTimestamp > windowEndMillis + gracePeriod.toMillis()) {
                    windowsToForward.put(windowStartMillis, districtSum);
                }
            }

            for(Map.Entry<Long, Double> entry : windowsToForward.entrySet()){
                long windowStartMillis = entry.getKey();
                double districtSum = entry.getValue();

                String outputKey = "district1_" + Instant.ofEpochMilli(windowStartMillis).toString();
                // Forwarding with the current stream timestamp which is appropriate for windowed output
                Record<String, Double> aggregatedRecord = new Record<>(outputKey, districtSum, currentStreamTimestamp);
                context.forward(aggregatedRecord);

                System.out.println("Forwarded aggregated district data (from Ward Sums) for window " +
                                   Instant.ofEpochMilli(windowStartMillis).toString() +
                                   ": sum=" + districtSum + " at stream time " + Instant.ofEpochMilli(currentStreamTimestamp));

                hourlyDistrictSumStore.delete(windowStartMillis);
            }
        } catch (UnsupportedOperationException uoe) {
             System.err.println("Warning during district punctuation: peekNextKey() is not implemented: " + uoe.getMessage());
        } catch (Exception e) {
            System.err.println("Error during district punctuation (from Ward Sums): " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void close() {
        System.out.println("DistrictAggregatorProcessor (from Ward Sums) closed.");
    }
}