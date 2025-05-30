package aggregator;

import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.streams.state.StoreBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class CustomKeyValueStoreBuilder<K, V> implements StoreBuilder<CustomKeyValueStore<K, V>> {

    private final String name;
    private final Serde<K> keySerde;
    private final Serde<V> valueSerde;
    private Map<String, String> logConfig = new HashMap<>();
    private boolean loggingEnabled = true; // Default to enabled for aggregation state
    private boolean cachingEnabled = false; // Kafka Streams doesn't auto-cache custom stores

    public CustomKeyValueStoreBuilder(String name, Serde<K> keySerde, Serde<V> valueSerde) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.keySerde = Objects.requireNonNull(keySerde, "keySerde cannot be null");
        this.valueSerde = Objects.requireNonNull(valueSerde, "valueSerde cannot be null");
    }

    @Override
    public StoreBuilder<CustomKeyValueStore<K, V>> withCachingEnabled() {
        this.cachingEnabled = true;
        return this;
    }

    @Override
    public StoreBuilder<CustomKeyValueStore<K, V>> withCachingDisabled() {
        this.cachingEnabled = false;
        return this;
    }

    @Override
    public StoreBuilder<CustomKeyValueStore<K, V>> withLoggingEnabled(Map<String, String> config) {
        this.loggingEnabled = true;
        this.logConfig = config != null ? new HashMap<>(config) : new HashMap<>();
        return this;
    }

    @Override
    public StoreBuilder<CustomKeyValueStore<K, V>> withLoggingDisabled() {
        this.loggingEnabled = false;
        this.logConfig.clear();
        return this;
    }

    @Override
    public CustomKeyValueStore<K, V> build() {
        return new CustomKeyValueStore<>(name, keySerde, valueSerde, loggingEnabled);
    }

    @Override
    public Map<String, String> logConfig() {
        return loggingEnabled ? logConfig : null;
    }

    @Override
    public boolean loggingEnabled() {
        return loggingEnabled;
    }

    @Override
    public String name() {
        return name;
    }

    public Serde<K> keySerde() {
        return keySerde;
    }

    public Serde<V> valueSerde() {
        return valueSerde;
    }
}