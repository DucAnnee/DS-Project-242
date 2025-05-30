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

public class DistrictPeakPowerMainApp {

    private static final String APPLICATION_ID = "iot-district-peak-power-app";
    private static final String BOOTSTRAP_SERVERS = "192.168.182.128:9092";
    // Input from original ward topics with raw data
    private static final List<String> INPUT_TOPICS = Arrays.asList("ward1", "ward2", "ward3");
    private static final String OUTPUT_TOPIC = "district1.1h.peak";
    private static final String PEAK_CONSUMPTION_STORE_NAME = "HourlyDistrictPeakConsumptionStore";

    public static void main(String[] args) {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, APPLICATION_ID);
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        // Use the original ConsumptionTimestampExtractor for raw HouseholdConsumption data
        props.put(StreamsConfig.DEFAULT_TIMESTAMP_EXTRACTOR_CLASS_CONFIG, ConsumptionTimestampExtractor.class.getName());
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        // Default value SerDe is HouseholdConsumptionSerde for raw data
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, HouseholdConsumptionSerde.class.getName());

        props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG, LogAndContinueExceptionHandler.class.getName());
        props.put(StreamsConfig.consumerPrefix(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG), "earliest");
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 5000); // 5 seconds


        Topology topology = new Topology();

        // SerDes
        Serde<String> stringSerde = Serdes.String();
        HouseholdConsumptionSerde householdConsumptionSerde = new HouseholdConsumptionSerde(); // For input
        Serde<Double> doubleSerde = Serdes.Double(); // For output peak value

        // Source Node consuming from multiple raw ward topics
        topology.addSource(
                "RawConsumptionSource",
                stringSerde.deserializer(),
                householdConsumptionSerde.deserializer(),
                INPUT_TOPICS.toArray(new String[0])
        );

        // ProcessorSupplier for the peak power processor
        ProcessorSupplier<String, HouseholdConsumption, String, Double> peakPowerProcessorSupplier =
            new ProcessorSupplier<String, HouseholdConsumption, String, Double>() {
                @Override
                public Processor<String, HouseholdConsumption, String, Double> get() {
                    return new DistrictPeakPowerProcessor(PEAK_CONSUMPTION_STORE_NAME);
                }

                @Override
                public Set<StoreBuilder<?>> stores() {
                    final CustomKeyValueStoreBuilder<Long, Double> storeBuilder =
                            new CustomKeyValueStoreBuilder<>(
                                    PEAK_CONSUMPTION_STORE_NAME,
                                    Serdes.Long(),   // Key for store: window start timestamp
                                    Serdes.Double()  // Value for store: peak consumption value
                            );
                    storeBuilder.withLoggingEnabled(new HashMap<>());
                    return Collections.singleton(storeBuilder);
                }
            };

        topology.addProcessor(
                "DistrictPeakPowerNode",
                peakPowerProcessorSupplier,
                "RawConsumptionSource"
        );

        // Sink Node to output the peak consumption data
        topology.addSink(
                "DistrictPeakSink",
                OUTPUT_TOPIC,
                stringSerde.serializer(),   // Key: String (e.g., "district1_peak_timestamp")
                doubleSerde.serializer(),   // Value: Double (the peak consumption)
                "DistrictPeakPowerNode"
        );

        System.out.println("District Peak Power Topology description:\n" + topology.describe());

        final KafkaStreams streams = new KafkaStreams(topology, props);
        final CountDownLatch latch = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread("district-peak-streams-shutdown-hook") {
            @Override
            public void run() {
                System.out.println("Closing District Peak Power Kafka Streams application (Shutdown Hook)...");
                streams.close();
                latch.countDown();
            }
        });

        try {
            streams.setStateListener((newState, oldState) -> {
                System.out.println("District Peak Power Kafka Streams state changed from " + oldState + " to " + newState);
                if (newState == KafkaStreams.State.ERROR) {
                    System.err.println("FATAL: District Peak Power Kafka Streams entered ERROR state. Shutting down.");
                    streams.close();
                    latch.countDown();
                }
            });
            streams.start();
            System.out.println("DistrictPeakPowerMainApp started. Consuming from " +
                               String.join(", ", INPUT_TOPICS) +
                               ", producing hourly district peak consumption to " + OUTPUT_TOPIC + ".");
            latch.await();
        } catch (Throwable e) {
            System.err.println("Unhandled exception in DistrictPeakPowerMainApp: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            if (streams.state().isRunningOrRebalancing()) {
                 System.out.println("Ensuring District Peak Power Kafka Streams is closed in finally block...");
                 streams.close();
            }
             System.out.println("DistrictPeakPowerMainApp main thread exiting.");
        }
        System.exit(0);
    }
}