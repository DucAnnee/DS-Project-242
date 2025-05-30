package aggregator;

import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.streams.KeyValue; // Import KeyValue
import org.apache.kafka.streams.processor.StateRestoreCallback;
import org.apache.kafka.streams.processor.StateStore;
import org.apache.kafka.streams.processor.StateStoreContext;
import org.apache.kafka.streams.state.KeyValueIterator;

import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

public class CustomKeyValueStore<K, V> implements StateStore {

    private final String name;
    private final boolean persistent;
    private final Serde<K> keySerde;
    private final Serde<V> valueSerde;

    private Map<K, V> internalMap;
    private StateStoreContext context;
    private boolean open = false;
    private int partition;

    public CustomKeyValueStore(String name, Serde<K> keySerde, Serde<V> valueSerde, boolean persistent) {
        this.name = name;
        this.keySerde = keySerde;
        this.valueSerde = valueSerde;
        this.persistent = persistent;
        this.internalMap = new ConcurrentHashMap<>();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void init(StateStoreContext context, StateStore root) {
        this.context = context;
        this.partition = context.taskId().partition();

        if (persistent) {
            System.out.println("CustomKeyValueStore '" + name + "' (partition " + partition + ") is persistent. Registering restore callback.");
            StateRestoreCallback restoreCallback = (keyBytes, valueBytes) -> {
                if (keyBytes == null) {
                    System.err.println("CustomKeyValueStore '" + name + "' (partition " + partition + ") received null key during restore. Skipping.");
                    return;
                }
                K key = keySerde.deserializer().deserialize(this.name(), keyBytes);
                if (valueBytes == null) {
                    internalMap.remove(key);
                } else {
                    V value = valueSerde.deserializer().deserialize(this.name(), valueBytes);
                    internalMap.put(key, value);
                }
            };
            context.register(this, restoreCallback);
        }
        this.open = true;
        System.out.println("CustomKeyValueStore '" + name + "' (partition " + partition + ") initialized. Persistent: " + persistent);
    }

    public void put(K key, V value) {
        if (!isOpen()) throw new IllegalStateException("Store " + name + " is not open.");
        if (key == null) {
            System.err.println("CustomKeyValueStore '" + name + "' (partition " + partition + "): Attempted to put null key. Skipping.");
            return;
        }
        if (value == null) {
            internalMap.remove(key);
        } else {
            internalMap.put(key, value);
        }
    }

    public V get(K key) {
        if (!isOpen()) throw new IllegalStateException("Store " + name + " is not open.");
        return internalMap.get(key);
    }

    public void delete(K key) {
        if (!isOpen()) throw new IllegalStateException("Store " + name + " is not open.");
        if (key == null) return;
        internalMap.remove(key);
    }

    public KeyValueIterator<K, V> all() {
        if (!isOpen()) throw new IllegalStateException("Store " + name + " is not open.");
        // Create a copy for iterator to avoid ConcurrentModificationException if map is modified
        // while iterating, especially if the underlying map isn't concurrent-safe for iteration AND modification.
        // ConcurrentHashMap's iterator is weakly consistent and won't throw CME.
        // However, iterating over a snapshot (new ConcurrentHashMap<>(internalMap)) can be safer
        // if strict point-in-time iteration is needed without seeing later modifications.
        final Iterator<Map.Entry<K, V>> mapIterator = new ConcurrentHashMap<>(internalMap).entrySet().iterator();

        return new KeyValueIterator<K, V>() {
            // private Map.Entry<K,V> currentEntry; // Not strictly needed if entry is fetched and used locally in next()

            @Override
            public void close() {
                // No-op for this simple map iterator
            }

            @Override
            public boolean hasNext() {
                return mapIterator.hasNext();
            }

            @Override
            public KeyValue<K, V> next() { // This is the single, correct next() method
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Map.Entry<K, V> entry = mapIterator.next();
                return new KeyValue<>(entry.getKey(), entry.getValue());
            }

            /**
             * This method is also part of the KeyValueIterator interface.
             * Implementing it properly for a map-based iterator without advancing
             * and caching can be tricky or inefficient. For a simple store,
             * throwing UnsupportedOperationException is an option if not used,
             * or implementing it if needed by Kafka Streams internals or your logic.
             * Kafka Streams might not call this if you only use basic iteration.
             */
            @Override
            public K peekNextKey() {
                // A proper implementation would require peeking into the underlying iterator
                // or fetching and caching. This is a simplified (and potentially costly) way,
                // or you could throw UnsupportedOperationException if not used.
                // For a truly robust custom store, you'd need to handle this more carefully.
                // For this example, let's assume it might not be heavily used or provide a basic impl.
                // This basic implementation is NOT efficient as it re-iterates to find the "next" key.
                // Throwing an exception is often safer if a proper peek isn't implemented.
                // if (mapIterator.hasNext()) {
                //    Map.Entry<K,V> entry = mapIterator.next(); // This advances the main iterator, which is bad for peek
                //    // To do this properly, you'd need a separate mechanism or a resettable iterator
                //    // or a way to buffer the next element.
                // }
                // For now, let's throw to indicate it's not properly/efficiently implemented.
                throw new UnsupportedOperationException("peekNextKey() not efficiently implemented for this custom store.");
            }
        };
    }

    @Override
    public void flush() {
        System.out.println("CustomKeyValueStore '" + name + "' (partition " + partition + ") flush called.");
    }

    @Override
    public void close() {
        this.open = false;
        System.out.println("CustomKeyValueStore '" + name + "' (partition " + partition + ") closed.");
    }

    @Override
    public boolean persistent() {
        return persistent;
    }

    @Override
    public boolean isOpen() {
        return open;
    }
}