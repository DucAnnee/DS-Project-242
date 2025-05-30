package aggregator;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

import java.time.Instant;
import java.time.format.DateTimeParseException;

public class WardSumTimestampExtractor implements TimestampExtractor {

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Object keyObject = record.key();
        if (keyObject instanceof String) {
            String key = (String) keyObject;
            // Expected key format: "wardX_YYYY-MM-DDTHH:MM:SSZ" or "districtX_YYYY-MM-DDTHH:MM:SSZ"
            // Example: "ward1_2025-05-29T08:00:00Z"
            int lastUnderscoreIndex = key.lastIndexOf('_');
            if (lastUnderscoreIndex != -1 && lastUnderscoreIndex < key.length() - 1) {
                String timestampString = key.substring(lastUnderscoreIndex + 1);
                try {
                    Instant instant = Instant.parse(timestampString);
                    return instant.toEpochMilli();
                } catch (DateTimeParseException e) {
                    System.err.println("Error parsing timestamp from key '" + key + "': " + e.getMessage());
                }
            } else {
                System.err.println("Unexpected key format for timestamp extraction: " + key);
            }
        } else {
            System.err.println("TimestampExtractor: Record key is not a String. Key: " + keyObject + ", Type: " + (keyObject != null ? keyObject.getClass().getName() : "null") + ". Using partition time.");
        }
        return partitionTime; // Fallback
    }
}