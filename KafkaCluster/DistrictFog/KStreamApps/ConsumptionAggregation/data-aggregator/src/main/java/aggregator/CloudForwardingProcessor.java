package aggregator; // Or your chosen package

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.DoubleSerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.processor.api.RecordMetadata;

import java.time.Instant; // For more readable timestamp logging
import java.util.Properties;

// This processor does not forward to another KStream node, so output types are Void, Void
public class CloudForwardingProcessor implements Processor<String, Double, Void, Void> {

    private final String cloudBootstrapServers;
    private final String targetCloudTopic;
    private KafkaProducer<String, Double> cloudProducer;
    private ProcessorContext<Void, Void> context; // Context for metadata, not for forwarding

    public CloudForwardingProcessor(String cloudBootstrapServers, String targetCloudTopic) {
        this.cloudBootstrapServers = cloudBootstrapServers;
        this.targetCloudTopic = targetCloudTopic;
    }

    @Override
    public void init(ProcessorContext<Void, Void> context) {
        this.context = context;
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, this.cloudBootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, DoubleSerializer.class.getName());
        // producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        // producerProps.put(ProducerConfig.RETRIES_CONFIG, 3);

        this.cloudProducer = new KafkaProducer<>(producerProps);
        System.out.println("CloudForwardingProcessor initialized for target topic '" + targetCloudTopic +
                           "' on cloud servers: " + cloudBootstrapServers);
    }

    @Override
    public void process(Record<String, Double> record) {
        String sourceTopic = this.context.recordMetadata().map(RecordMetadata::topic).orElse("N/A_FOG_SOURCE");

        // --- New print statement to log received data ---
        System.out.printf("CloudForwardingProcessor: Received from Fog Topic '%s': Key='%s', Value='%.2f', Timestamp='%s' (%d)%n",
                sourceTopic,
                record.key(),
                record.value(), // Assuming value is Double, formatted to 2 decimal places
                Instant.ofEpochMilli(record.timestamp()).toString(), // Human-readable timestamp
                record.timestamp()); // Raw epoch milliseconds
        // --- End of new print statement ---

        // Create a new ProducerRecord for the cloud, preserving key, value, and timestamp
        ProducerRecord<String, Double> cloudRecord = new ProducerRecord<>(
                this.targetCloudTopic,
                null, // Partition
                record.timestamp(),
                record.key(),
                record.value()
                // record.headers()
        );

        try {
            cloudProducer.send(cloudRecord, (metadata, exception) -> {
                if (exception != null) {
                    System.err.println("Failed to send record to cloud topic '" + this.targetCloudTopic +
                                       "' from fog topic '" + sourceTopic + "': key=" + record.key() +
                                       ", error=" + exception.getMessage());
                } else {
                    // System.out.println("Successfully forwarded record to cloud topic '" + metadata.topic() +
                    //                    "' partition " + metadata.partition() + " offset " + metadata.offset() +
                    //                    " from fog topic '" + sourceTopic + "': key=" + record.key());
                }
            });
        } catch (Exception e) {
            System.err.println("Synchronous error sending record to cloud topic '" + this.targetCloudTopic +
                               "' from fog topic '" + sourceTopic + "': key=" + record.key() +
                               ", error=" + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (this.cloudProducer != null) {
            this.cloudProducer.flush();
            this.cloudProducer.close();
            System.out.println("CloudForwardingProcessor closed for target topic: " + targetCloudTopic);
        }
    }
}