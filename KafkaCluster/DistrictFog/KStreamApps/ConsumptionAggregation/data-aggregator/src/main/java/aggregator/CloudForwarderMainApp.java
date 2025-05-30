package aggregator; // Or a new package like com.iot.forwarder

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.clients.consumer.ConsumerConfig; // For AUTO_OFFSET_RESET_CONFIG
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;

public class CloudForwarderMainApp {

    private static final String APPLICATION_ID = "iot-fog-to-cloud-forwarder-app";
    // BOOTSTRAP_SERVERS for the FOG Kafka cluster (where KStreams app connects to read)
    private static final String FOG_BOOTSTRAP_SERVERS = "192.168.182.128:9092";

    // Placeholder IP for the CLOUD Kafka cluster
    // Replace "YOUR_CLOUD_KAFKA_IP:PORT" with the actual cloud Kafka broker address
    private static final String CLOUD_BOOTSTRAP_SERVERS = "192.168.182.131:9092"; // << CHANGE THIS LATER

    // Fog topics (source)
    private static final String FOG_TOPIC_DISTRICT1_MIN_SUM = "district1.1m.sum";
    private static final String FOG_TOPIC_DISTRICT1_HOUR_SUM = "district1.1h.sum";
    private static final String FOG_TOPIC_DISTRICT1_HOUR_PEAK = "district1.1h.peak";

    // Cloud topics (target - names are the same as per requirement)
    private static final String CLOUD_TOPIC_DISTRICT1_MIN_SUM = "district1.1m.sum";
    private static final String CLOUD_TOPIC_DISTRICT1_HOUR_SUM = "district1.1h.sum";
    private static final String CLOUD_TOPIC_DISTRICT1_HOUR_PEAK = "district1.1h.peak";


    public static void main(String[] args) {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, APPLICATION_ID);
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, FOG_BOOTSTRAP_SERVERS); // Connect to Fog
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.Double().getClass().getName());
        // Timestamps are already part of the records from previous aggregations, KStreams will preserve them.
        // No custom timestamp extractor needed here if the source records have correct timestamps.

        props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG, LogAndContinueExceptionHandler.class.getName());
        props.put(StreamsConfig.consumerPrefix(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG), "earliest"); // Or "earliest"
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 5000); // 5 seconds

        Topology topology = new Topology();

        // --- Path for district1.1min.sum ---
        topology.addSource(
                "FogSource_MinSum",
                Serdes.String().deserializer(),
                Serdes.Double().deserializer(),
                FOG_TOPIC_DISTRICT1_MIN_SUM);
        topology.addProcessor(
                "CloudForwarder_MinSum",
                () -> new CloudForwardingProcessor(CLOUD_BOOTSTRAP_SERVERS, CLOUD_TOPIC_DISTRICT1_MIN_SUM),
                "FogSource_MinSum");

        // --- Path for district1.1h.sum ---
        topology.addSource(
                "FogSource_HourSum",
                Serdes.String().deserializer(),
                Serdes.Double().deserializer(),
                FOG_TOPIC_DISTRICT1_HOUR_SUM);
        topology.addProcessor(
                "CloudForwarder_HourSum",
                () -> new CloudForwardingProcessor(CLOUD_BOOTSTRAP_SERVERS, CLOUD_TOPIC_DISTRICT1_HOUR_SUM),
                "FogSource_HourSum");

        // --- Path for district1.1h.peak ---
        topology.addSource(
                "FogSource_HourPeak",
                Serdes.String().deserializer(),
                Serdes.Double().deserializer(),
                FOG_TOPIC_DISTRICT1_HOUR_PEAK);
        topology.addProcessor(
                "CloudForwarder_HourPeak",
                () -> new CloudForwardingProcessor(CLOUD_BOOTSTRAP_SERVERS, CLOUD_TOPIC_DISTRICT1_HOUR_PEAK),
                "FogSource_HourPeak");

        System.out.println("Fog-to-Cloud Forwarder Topology description:\n" + topology.describe());

        final KafkaStreams streams = new KafkaStreams(topology, props);
        final CountDownLatch latch = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread("fog-to-cloud-forwarder-shutdown-hook") {
            @Override
            public void run() {
                System.out.println("Closing Fog-to-Cloud Forwarder Kafka Streams application (Shutdown Hook)...");
                streams.close();
                latch.countDown();
            }
        });

        try {
            streams.setStateListener((newState, oldState) -> {
                System.out.println("Fog-to-Cloud Forwarder Kafka Streams state changed from " + oldState + " to " + newState);
                if (newState == KafkaStreams.State.ERROR) {
                    System.err.println("FATAL: Fog-to-Cloud Forwarder Kafka Streams entered ERROR state. Shutting down.");
                    streams.close();
                    latch.countDown();
                }
            });
            streams.start();
            System.out.println("CloudForwarderMainApp started. Forwarding data from Fog to Cloud.");
            latch.await();
        } catch (Throwable e) {
            System.err.println("Unhandled exception in CloudForwarderMainApp: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            if (streams.state().isRunningOrRebalancing()) {
                 System.out.println("Ensuring Fog-to-Cloud Forwarder Kafka Streams is closed in finally block...");
                 streams.close();
            }
             System.out.println("CloudForwarderMainApp main thread exiting.");
        }
        System.exit(0);
    }
}