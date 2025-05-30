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

import java.util.Collections;
import java.util.HashMap;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

public class DataAggregatorMainApp {

    private static final String APPLICATION_ID = "iot-data-aggregator-app-3";
    private static final String BOOTSTRAP_SERVERS = "192.168.182.128:9092";
    private static final String INPUT_TOPIC = "ward3";
    private static final String OUTPUT_TOPIC = "ward3.1h.sum";
    private static final String AGGREGATION_STORE_NAME = "HourlyConsumptionStore";

    public static void main(String[] args) {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, APPLICATION_ID);
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        // Use the custom timestamp extractor
        props.put(StreamsConfig.DEFAULT_TIMESTAMP_EXTRACTOR_CLASS_CONFIG, ConsumptionTimestampExtractor.class.getName());
        // Default SerDes for keys, values will be handled by specific SerDes in topology
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, HouseholdConsumptionSerde.class.getName()); // Can be default if source specifies

        // Configuration for handling deserialization errors
        props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG, LogAndContinueExceptionHandler.class.getName());
        // Ensure data is processed from the beginning if no offset is found
        props.put(StreamsConfig.consumerPrefix(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG), "earliest");

        // Increase commit interval for potentially higher throughput in production, default is 30s for at_least_once
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 5000); // e.g., 5 seconds


        Topology topology = new Topology();

        // SerDes
        Serde<String> stringSerde = Serdes.String();
        HouseholdConsumptionSerde householdConsumptionSerde = new HouseholdConsumptionSerde();
        Serde<Double> doubleSerde = Serdes.Double(); // For the aggregated sum

        // Source Node
        topology.addSource(
                "ConsumptionSource",
                stringSerde.deserializer(),
                householdConsumptionSerde.deserializer(),
                INPUT_TOPIC
        );

        // Processor Node with Custom Store
        ProcessorSupplier<String, HouseholdConsumption, String, Double> aggregatorProcessorSupplier =
            new ProcessorSupplier<String, HouseholdConsumption, String, Double>() {
                @Override
                public Processor<String, HouseholdConsumption, String, Double> get() {
                    return new DataAggregatorProcessor(AGGREGATION_STORE_NAME);
                }

                @Override
                public Set<StoreBuilder<?>> stores() {
                    final CustomKeyValueStoreBuilder<Long, Double> storeBuilder =
                            new CustomKeyValueStoreBuilder<>(
                                    AGGREGATION_STORE_NAME,
                                    Serdes.Long(),   // Key for store: window start timestamp
                                    Serdes.Double()  // Value for store: aggregated consumption
                            );
                    // Enable changelogging for fault tolerance (true by default in our builder)
                    storeBuilder.withLoggingEnabled(new HashMap<>()); // Pass empty map for default log configs
                    return Collections.singleton(storeBuilder);
                }
            };

        topology.addProcessor(
                "AggregatorNode",
                aggregatorProcessorSupplier,
                "ConsumptionSource"
        );

        // Sink Node to output the aggregated data
        topology.addSink(
                "AggregationSink",
                OUTPUT_TOPIC,
                stringSerde.serializer(),   // Key: String (e.g., "ward1_2023-01-01T10:00:00Z")
                doubleSerde.serializer(),   // Value: Double (the sum)
                "AggregatorNode"
        );

        System.out.println("Topology description:\n" + topology.describe());

        final KafkaStreams streams = new KafkaStreams(topology, props);
        final CountDownLatch latch = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread("streams-shutdown-hook") {
            @Override
            public void run() {
                System.out.println("Closing Kafka Streams application (Shutdown Hook)...");
                streams.close();
                latch.countDown();
            }
        });

        try {
            streams.setStateListener((newState, oldState) -> {
                System.out.println("Kafka Streams state changed from " + oldState + " to " + newState);
                if (newState == KafkaStreams.State.ERROR) {
                    System.err.println("FATAL: Kafka Streams entered ERROR state. Shutting down.");
                    // Optionally, you can try to close streams more gracefully or trigger alerts
                    streams.close(); // Attempt to close
                    latch.countDown(); // Release main thread
                }
            });
            streams.start();
            System.out.println("DataAggregatorApplication started. Consuming from " + INPUT_TOPIC +
                               ", producing hourly sums to " + OUTPUT_TOPIC + ".");
            latch.await();
        } catch (Throwable e) {
            System.err.println("Unhandled exception in DataAggregatorMainApp: " + e.getMessage());
            e.printStackTrace();
            System.exit(1); // Indicate abnormal termination
        } finally {
            if (streams.state().isRunningOrRebalancing()) {
                 System.out.println("Ensuring Kafka Streams is closed in finally block...");
                 streams.close();
            }
             System.out.println("DataAggregatorApplication main thread exiting.");
        }
        System.exit(0); // Indicate successful shutdown
    }
}