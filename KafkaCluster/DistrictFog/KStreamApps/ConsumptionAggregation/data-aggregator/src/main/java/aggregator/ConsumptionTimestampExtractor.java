package aggregator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

public class ConsumptionTimestampExtractor implements TimestampExtractor {
    // No need for ObjectMapper here if HouseholdConsumption object is readily available with parsed timestamp

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Object value = record.value();
        if (value instanceof HouseholdConsumption) {
            HouseholdConsumption consumptionData = (HouseholdConsumption) value;
            try {
                if (consumptionData.getTimestamp() == null) {
                     System.err.println("Timestamp field is null in HouseholdConsumption object for key: " + record.key());
                     return partitionTime; // or throw, or a specific error timestamp
                }
                OffsetDateTime odt = OffsetDateTime.parse(consumptionData.getTimestamp());
                return odt.toInstant().toEpochMilli();
            } catch (DateTimeParseException e) {
                System.err.println("Error parsing timestamp string '" + consumptionData.getTimestamp() + "' for key: " + record.key() + ". Error: " + e.getMessage());
                return partitionTime; // Fallback to partition time or handle as an error
            } catch (Exception e) {
                System.err.println("Unexpected error extracting timestamp for key: " + record.key() + ". Error: " + e.getMessage());
                e.printStackTrace();
                return partitionTime;
            }
        }
        // Optional: handle if value is JsonNode, though HouseholdConsumptionSerde should provide the POJO
        // else if (value instanceof JsonNode) { ... }

        System.err.println("TimestampExtractor: Record value is not of type HouseholdConsumption or known type. Key: " + record.key() + ", Type: " + (value != null ? value.getClass().getName() : "null") + ". Using partition time.");
        return partitionTime; // Fallback for unexpected types or errors
    }
}