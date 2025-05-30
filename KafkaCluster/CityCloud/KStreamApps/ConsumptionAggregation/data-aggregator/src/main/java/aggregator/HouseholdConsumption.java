package aggregator;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class HouseholdConsumption {
    private final String meterId;
    private final String timestamp; // Keep as String for initial parsing, convert to OffsetDateTime as needed
    private final double consumption;

    @JsonCreator
    public HouseholdConsumption(
            @JsonProperty("meter_id") String meterId,
            @JsonProperty("timestamp") String timestamp,
            @JsonProperty("consumption") double consumption) {
        this.meterId = meterId;
        this.timestamp = timestamp;
        this.consumption = consumption;
    }

    @JsonProperty("meter_id")
    public String getMeterId() {
        return meterId;
    }

    @JsonProperty("timestamp")
    public String getTimestamp() {
        return timestamp;
    }

    @JsonProperty("consumption")
    public double getConsumption() {
        return consumption;
    }

    @Override
    public String toString() {
        return "HouseholdConsumption{" +
               "meterId='" + meterId + '\'' +
               ", timestamp='" + timestamp + '\'' +
               ", consumption=" + consumption +
               '}';
    }
}