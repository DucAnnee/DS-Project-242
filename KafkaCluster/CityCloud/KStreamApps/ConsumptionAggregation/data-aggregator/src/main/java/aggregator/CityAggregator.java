package aggregator;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorSupplier;
import org.apache.kafka.streams.state.StoreBuilder;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

public class CityAggregator {

    private static final String APPLICATION_ID = "iot-city-hourly-sum-aggregator-app-1"; // Updated App ID
    private static final String BOOTSTRAP_SERVERS = "192.168.182.131:9092";
    // Input from multiple ward hourly sum topics
    private static final List<String> INPUT_TOPICS = Arrays.asList(
            "district1.1h.sum", // Assuming this is the output of DataAggregator for ward1
            "district2.1h.sum", // Assuming this is the output of DataAggregator for ward2
            "district3.1h.sum" // Assuming this is the output of DataAggregator for ward3
    );
    private static final String OUTPUT_TOPIC = "city.1h.sum";
    // Store name can be the same as it's scoped by Application ID, or changed for
    // clarity
    private static final String AGGREGATION_STORE_NAME = "CityHourlyCombinedSumStore";

    public static void main(String[] args) {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, APPLICATION_ID);
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        // Use the NEW custom timestamp extractor for ward hourly sum messages
        props.put(StreamsConfig.DEFAULT_TIMESTAMP_EXTRACTOR_CLASS_CONFIG, WardSumTimestampExtractor.class.getName());
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        // Default value SerDe is now Double, as we consume Doubles
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.Double().getClass().getName());

        props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
                LogAndContinueExceptionHandler.class.getName());
        props.put(StreamsConfig.consumerPrefix(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG), "earliest");
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 5000); // 5 seconds

        Topology topology = new Topology();

        // SerDes
        Serde<String> stringSerde = Serdes.String();
        Serde<Double> doubleSerde = Serdes.Double(); // Used for both input value and output value

        // Source Node consuming from multiple ward hourly sum topics
        topology.addSource(
                "DistrictHourlySumSource",
                stringSerde.deserializer(), // Key is String (e.g., "ward1_timestamp")
                doubleSerde.deserializer(), // Value is Double (hourly sum for the ward)
                INPUT_TOPICS.toArray(new String[0]));

        // ProcessorSupplier now takes <String, Double, String, Double>
        ProcessorSupplier<String, Double, String, Double> cityAggregatorProcessorSupplier = new ProcessorSupplier<String, Double, String, Double>() {
            @Override
            public Processor<String, Double, String, Double> get() {
                return new CityAggregatorProcessor(AGGREGATION_STORE_NAME);
            }

            @Override
            public Set<StoreBuilder<?>> stores() {
                final CustomKeyValueStoreBuilder<Long, Double> storeBuilder = new CustomKeyValueStoreBuilder<>(
                        AGGREGATION_STORE_NAME,
                        Serdes.Long(), // Key for store: window start timestamp (epoch millis)
                        Serdes.Double() // Value for store: aggregated district consumption
                );
                storeBuilder.withLoggingEnabled(new HashMap<>());
                return Collections.singleton(storeBuilder);
            }
        };

        topology.addProcessor(
                "CityAggregatorNode",
                cityAggregatorProcessorSupplier,
                "DistrictHourlySumSource" // Connect to the new source node name
        );

        topology.addSink(
                "CityAggregationSink",
                OUTPUT_TOPIC,
                stringSerde.serializer(), // Key: String (e.g., "district1_timestamp")
                doubleSerde.serializer(), // Value: Double (the district sum)
                "CityAggregatorNode");

        System.out.println("City Aggregator (from District Sums) Topology description:\n" + topology.describe());

        final KafkaStreams streams = new KafkaStreams(topology, props);
        final CountDownLatch latch = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread("district-hourly-streams-shutdown-hook") {
            @Override
            public void run() {
                System.out.println("Closing City (from District Sums) Kafka Streams application (Shutdown Hook)...");
                streams.close();
                latch.countDown();
            }
        });

        try {
            streams.setStateListener((newState, oldState) -> {
                System.out.println(
                        "City (from District Sums) Kafka Streams state changed from " + oldState + " to " + newState);
                if (newState == KafkaStreams.State.ERROR) {
                    System.err.println(
                            "FATAL: City (from District Sums) Kafka Streams entered ERROR state. Shutting down.");
                    streams.close();
                    latch.countDown();
                }
            });
            streams.start();
            System.out.println("CityAggregatorApplication (from District Sums) started. Consuming from " +
                    String.join(", ", INPUT_TOPICS) +
                    ", producing hourly city sums to " + OUTPUT_TOPIC + ".");
            latch.await();
        } catch (Throwable e) {
            System.err.println("Unhandled exception in CityAggregatorMainApp (from District Sums): " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            if (streams.state().isRunningOrRebalancing()) {
                System.out.println("Ensuring City (from District Sums) Kafka Streams is closed in finally block...");
                streams.close();
            }
            System.out.println("CityAggregatorApplication (from District Sums) main thread exiting.");
        }
        System.exit(0);
    }
}