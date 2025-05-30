package aggregator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import java.io.IOException;
import java.util.Map;

public class HouseholdConsumptionSerde implements Serde<HouseholdConsumption> {

    private final ObjectMapper objectMapper;

    public HouseholdConsumptionSerde() {
        this.objectMapper = new ObjectMapper();
        // Register JavaTimeModule if you plan to deserialize timestamp string to OffsetDateTime directly in POJO
        // For now, HouseholdConsumption keeps it as String, parsing happens in processor or timestamp extractor
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    public Serializer<HouseholdConsumption> serializer() {
        return new Serializer<HouseholdConsumption>() {
            @Override
            public void configure(Map<String, ?> configs, boolean isKey) {
                // No-op
            }

            @Override
            public byte[] serialize(String topic, HouseholdConsumption data) {
                if (data == null) {
                    return null;
                }
                try {
                    return objectMapper.writeValueAsBytes(data);
                } catch (IOException e) {
                    throw new SerializationException("Error serializing HouseholdConsumption to JSON", e);
                }
            }

            @Override
            public void close() {
                // No-op
            }
        };
    }

    @Override
    public Deserializer<HouseholdConsumption> deserializer() {
        return new Deserializer<HouseholdConsumption>() {
            @Override
            public void configure(Map<String, ?> configs, boolean isKey) {
                // No-op
            }

            @Override
            public HouseholdConsumption deserialize(String topic, byte[] data) {
                if (data == null || data.length == 0) {
                    return null;
                }
                try {
                    return objectMapper.readValue(data, HouseholdConsumption.class);
                } catch (IOException e) {
                    throw new SerializationException("Error deserializing JSON to HouseholdConsumption", e);
                }
            }

            @Override
            public void close() {
                // No-op
            }
        };
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        serializer().configure(configs, isKey);
        deserializer().configure(configs, isKey);
    }

    @Override
    public void close() {
        serializer().close();
        deserializer().close();
    }
}