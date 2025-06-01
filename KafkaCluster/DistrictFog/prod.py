import json
from kafka import KafkaConsumer, KafkaProducer
from kafka.errors import KafkaError, KafkaConfigurationError
from datetime import datetime, timezone, timedelta
import time
import logging

# --- Configuration ---
SOURCE_KAFKA_BROKER = "localhost:9092"
DEST_KAFKA_BROKER = "10.1.1.4:9092"

# Topics to relay. The script will consume from these on the source
# and produce to the same topic names on the destination.
RELAY_TOPICS = [
    "district1.1h.sum",
    "district1.1h.peak",  # As specified by user
    "district1.1m.sum",
]
# Use a unique group_id for this relay consumer
CONSUMER_GROUP_ID = "kafka-topic-relay-group-v1"

# --- Logging Setup ---
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(levelname)s - %(module)s - %(message)s",
    handlers=[logging.StreamHandler()],
)
logger = logging.getLogger(__name__)


# --- Helper Functions for Kafka ---
# We assume messages are JSON, consistent with previous scripts.
# If messages could be other formats, a bytes pass-through would be better.
def json_deserializer(data_bytes):
    """Deserializes JSON bytes to a Python dictionary."""
    if data_bytes is None:
        return None
    try:
        return json.loads(data_bytes.decode("utf-8"))
    except json.JSONDecodeError as e:
        # Log error but return the raw bytes if it's not JSON,
        # so we can still try to relay it. Or, decide to skip.
        # For now, let's log and return None to skip, assuming JSON is expected.
        logger.error(
            f"Error decoding JSON: {data_bytes}. Error: {e}. Message will be skipped."
        )
        return None


def json_serializer(data_dict):
    """Serializes a Python dictionary to JSON bytes."""
    if isinstance(
        data_dict, bytes
    ):  # If it's already bytes (e.g. from failed deserialization)
        return data_dict
    try:
        return json.dumps(data_dict).encode("utf-8")
    except TypeError as e:
        logger.error(f"Error serializing data to JSON: {data_dict}. Error: {e}")
        return None  # Or raise, depending on desired error handling


# --- Kafka Producer Callbacks ---
def on_send_success(record_metadata):
    """Callback for successful message production to destination."""
    logger.debug(
        f"Message relayed successfully: Topic={record_metadata.topic}, "
        f"Partition={record_metadata.partition}, Offset={record_metadata.offset} "
        f"to {DEST_KAFKA_BROKER}"
    )


def on_send_error(excp):
    """Callback for errors during message production to destination."""
    logger.error(f"Error relaying message to {DEST_KAFKA_BROKER}", exc_info=excp)


# --- Main Application Logic ---
def cloud_prod_worker():
    """
    Main function to run the Kafka consumer (source) and producer (destination).
    """
    source_consumer = None
    dest_producer = None
    running = True

    try:
        # --- Initialize Kafka Consumer for Source Broker ---
        logger.info(
            f"Attempting to connect to SOURCE Kafka broker at {SOURCE_KAFKA_BROKER}"
        )
        source_consumer = KafkaConsumer(
            *RELAY_TOPICS,
            bootstrap_servers=SOURCE_KAFKA_BROKER,
            value_deserializer=json_deserializer,  # Assumes messages are JSON
            group_id=CONSUMER_GROUP_ID,
            auto_offset_reset="latest",  # Start from the beginning if no offset committed
            consumer_timeout_ms=1000,  # Timeout in ms to unblock the consumer loop
        )
        logger.info(
            f"Kafka Consumer connected to SOURCE: {SOURCE_KAFKA_BROKER} for topics: {RELAY_TOPICS}"
        )

        # --- Initialize Kafka Producer for Destination Broker ---
        logger.info(
            f"Attempting to connect to DESTINATION Kafka broker at {DEST_KAFKA_BROKER}"
        )
        dest_producer = KafkaProducer(
            bootstrap_servers=DEST_KAFKA_BROKER,
            value_serializer=json_serializer,  # Assumes messages are JSON
            retries=5,  # Retry sending a message 5 times
            acks="all",  # Wait for all in-sync replicas to acknowledge
            # linger_ms=10,                   # Optional: Batch messages for 10ms
            # request_timeout_ms=30000,       # Optional: Timeout for producer requests
        )
        logger.info(f"Kafka Producer connected to DESTINATION: {DEST_KAFKA_BROKER}")

        logger.info("Starting Kafka message relay loop...")
        while running:
            try:
                for (
                    message
                ) in source_consumer:  # This loop blocks until a message or timeout
                    if not running:
                        break

                    logger.debug(
                        f"Consumed from SOURCE {message.topic} (Partition {message.partition}, Offset {message.offset})"
                    )

                    # The message.value is already deserialized by json_deserializer
                    # If deserialization failed, message.value would be None (based on current helper)
                    if message.value is None:
                        logger.warning(
                            f"Skipping relay for message from {message.topic} due to previous deserialization error or null value."
                        )
                        continue

                    # Relay the message to the same topic on the destination broker
                    # The key, if present, should also be relayed.
                    # Kafka messages have key, value, headers, timestamp etc.
                    # For a simple relay of value:
                    dest_producer.send(
                        topic=message.topic,
                        value=message.value,
                        key=message.key,  # Relay the key as well
                        # To relay headers, you'd iterate through message.headers
                        # headers=message.headers
                    ).add_callback(on_send_success).add_errback(on_send_error)

                    # For higher throughput, consider flushing the producer periodically
                    # or based on batch size rather than after every message.
                    # dest_producer.flush() # This would make sends synchronous, reducing throughput

                if not running:  # Check after inner loop if shutdown initiated
                    break

            except StopIteration:  # Triggered by consumer_timeout_ms
                logger.debug(
                    "Source consumer poll timed out. No new messages in this interval."
                )
                if not running:
                    break
                continue

            except KafkaError as ke:
                logger.error(f"Kafka error during relay loop: {ke}", exc_info=True)
                # Depending on the error, you might want to retry or stop
                # For now, we'll try to continue, but a serious error might require stopping.
                # If it's a broker connection issue, it might resolve.
                time.sleep(5)  # Wait a bit before retrying the loop
            except Exception as e_loop:
                logger.error(
                    f"Unexpected error in main relay loop: {e_loop}", exc_info=True
                )
                running = False  # Stop on unexpected errors
                break

    except KeyboardInterrupt:
        logger.info("KeyboardInterrupt received. Initiating graceful shutdown...")
        running = False
    except KafkaConfigurationError as kce:  # Specific error for config issues
        logger.error(
            f"Kafka Configuration Error: {kce}. Please check broker addresses and settings.",
            exc_info=True,
        )
        running = False
    except KafkaError as ke_init:  # Errors during Kafka client initialization
        logger.error(f"Fatal Kafka initialization error: {ke_init}", exc_info=True)
        running = False
    except Exception as e_outer:
        logger.error(f"An critical unexpected error occurred: {e_outer}", exc_info=True)
        running = False
    finally:
        logger.info("Starting final cleanup...")

        if dest_producer:
            try:
                logger.info(
                    f"Flushing messages from destination producer ({DEST_KAFKA_BROKER})..."
                )
                dest_producer.flush(timeout=10)  # Wait up to 10 seconds
                logger.info("Destination producer flushed.")
            except KafkaError as e_flush:
                logger.error(f"Error flushing destination producer: {e_flush}")
            except Exception as e:
                logger.error(f"Unexpected error flushing destination producer: {e}")
            finally:
                dest_producer.close()
                logger.info(
                    f"Kafka Producer for DESTINATION ({DEST_KAFKA_BROKER}) closed."
                )

        if source_consumer:
            source_consumer.close()
            logger.info(f"Kafka Consumer for SOURCE ({SOURCE_KAFKA_BROKER}) closed.")

        logger.info("Kafka Topic Relay application shutdown complete.")


if __name__ == "__main__":
    # --- Prerequisites for running this script: ---
    # 1. Source Kafka broker running at SOURCE_KAFKA_BROKER.
    # 2. Destination Kafka broker running at DEST_KAFKA_BROKER and accessible.
    # 3. The RELAY_TOPICS must exist on the source broker.
    # 4. The RELAY_TOPICS should ideally exist on the destination broker,
    #    or auto topic creation must be enabled on the destination broker.
    #    If auto-creation is not enabled, you'll need to create them manually:
    #    Example for one topic on destination (using Kafka CLI for destination broker):
    #    kafka-topics.sh --create --topic district1.1h.sum --bootstrap-server 10.1.1.4:9092 --partitions <num_partitions> --replication-factor <num_replicas>
    #    (match partitions/replication with source or as desired for destination)
    cloud_prod_worker()
