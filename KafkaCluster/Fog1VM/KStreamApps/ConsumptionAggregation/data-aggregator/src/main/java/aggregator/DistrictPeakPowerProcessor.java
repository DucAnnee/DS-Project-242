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
// No need for Optional import if only used in .map().orElse() chain

public class DistrictPeakPowerProcessor implements Processor<String, HouseholdConsumption, String, Double> {

    private ProcessorContext<String, Double> context;
    private CustomKeyValueStore<Long, Double> hourlyDistrictPeakStore;
    private final String storeName;
    private final Duration windowSize = Duration.ofHours(1);
    private final Duration gracePeriod = Duration.ofMinutes(5); // Grace period for late arriving data

    public DistrictPeakPowerProcessor(String storeName) {
        this.storeName = storeName;
    }

    @Override
    public void init(ProcessorContext<String, Double> context) {
        this.context = context;
        this.hourlyDistrictPeakStore = context.getStateStore(storeName);
        if (this.hourlyDistrictPeakStore == null) {
            throw new IllegalStateException("State store '" + storeName + "' not found. Check topology.");
        }

        this.context.schedule(Duration.ofMinutes(1), PunctuationType.STREAM_TIME, this::punctuateHourlyPeak);
        System.out.println("DistrictPeakPowerProcessor initialized with store: " + storeName + " and scheduled punctuation.");
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
            long windowStartEpochMillis = recordTimestamp.truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli();
            double currentConsumption = data.getConsumption();

            Double currentPeakInWindow = hourlyDistrictPeakStore.get(windowStartEpochMillis);
            if (currentPeakInWindow == null) {
                // If no peak recorded yet for this window, the current consumption is the peak.
                // Or initialize with Double.NEGATIVE_INFINITY if you prefer, but Math.max handles nulls if we ensure first value sets it.
                currentPeakInWindow = Double.NEGATIVE_INFINITY;
            }

            double newPeakInWindow = Math.max(currentPeakInWindow, currentConsumption);
            hourlyDistrictPeakStore.put(windowStartEpochMillis, newPeakInWindow);

        } catch (Exception e) {
            System.err.println("Error processing record for peak power: key=" + record.key() + ", topic=" + topicName + ", value=" + data + ". Error: " + e.getMessage());
        }
    }

    private void punctuateHourlyPeak(long currentStreamTimestamp) {
        try (KeyValueIterator<Long, Double> iterator = hourlyDistrictPeakStore.all()) {
            Map<Long, Double> windowsToForward = new HashMap<>();

            while (iterator.hasNext()) {
                KeyValue<Long, Double> kv = iterator.next();
                long windowStartMillis = kv.key;
                double peakConsumption = kv.value; // This is the max consumption for the window

                long windowEndMillis = windowStartMillis + windowSize.toMillis();
                if (currentStreamTimestamp > windowEndMillis + gracePeriod.toMillis()) {
                    // This window is closed and past grace period
                    // Only forward if peak is not the initial NEGATIVE_INFINITY, meaning data was seen
                    if (peakConsumption > Double.NEGATIVE_INFINITY) {
                        windowsToForward.put(windowStartMillis, peakConsumption);
                    } else {
                        // Optionally, decide if you want to forward anything or log for empty/initial-state windows
                        // For now, we just ignore them if they remained at NEGATIVE_INFINITY
                        hourlyDistrictPeakStore.delete(windowStartMillis); // Clean up non-data windows
                    }
                }
            }

            for(Map.Entry<Long, Double> entry : windowsToForward.entrySet()){
                long windowStartMillis = entry.getKey();
                double peakValue = entry.getValue();

                String outputKey = "district1_peak_" + Instant.ofEpochMilli(windowStartMillis).toString();
                Record<String, Double> peakRecord = new Record<>(outputKey, peakValue, currentStreamTimestamp);
                context.forward(peakRecord);

                System.out.println("Forwarded district peak power for window " +
                                   Instant.ofEpochMilli(windowStartMillis).toString() +
                                   ": peak_consumption=" + peakValue + " at stream time " + Instant.ofEpochMilli(currentStreamTimestamp));

                hourlyDistrictPeakStore.delete(windowStartMillis); // Remove the processed window from the store
            }

        } catch (UnsupportedOperationException uoe) {
             System.err.println("Warning during district peak punctuation: peekNextKey() is not implemented: " + uoe.getMessage());
        } catch (Exception e) {
            System.err.println("Error during district peak punctuation: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void close() {
        System.out.println("DistrictPeakPowerProcessor closed.");
    }
}