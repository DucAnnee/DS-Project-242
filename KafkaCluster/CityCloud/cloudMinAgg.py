import json
from kafka import KafkaConsumer, KafkaProducer
from kafka.errors import KafkaError
from datetime import datetime, timezone
import time
import logging

# --- Configuration ---
KAFKA_BROKER = "localhost:9092"
SOURCE_TOPICS = ["district1.1m.sum", "district2.1m.sum"]
TARGET_TOPIC = "city.1m.sum"
# Use a unique group_id for your consumer group
CONSUMER_GROUP_ID = (
    "city-minute-aggregator-group-py-v8"  # Incremented version for new logic
)
EXPECTED_MESSAGES_PER_MINUTE_INTERVAL = 2  # 5 houses/ward * 3 wards

# --- Logging Setup ---
# Configure logging to provide insights into the application's behavior
logging.basicConfig(
    level=logging.INFO,  # Set to DEBUG for more verbose output
    format="%(asctime)s - %(levelname)s - %(module)s - %(message)s",
    handlers=[logging.StreamHandler()],  # Log to console
)
logger = logging.getLogger(__name__)


# --- Helper Functions ---
def json_deserializer(data_bytes):
    """Deserializes JSON bytes to a Python dictionary."""
    if data_bytes is None:
        return None
    try:
        return json.loads(data_bytes.decode("utf-8"))
    except json.JSONDecodeError as e:
        logger.error(f"Error decoding JSON: {data_bytes}. Error: {e}")
        return None


def json_serializer(data_dict):
    """Serializes a Python dictionary to JSON bytes."""
    try:
        return json.dumps(data_dict).encode("utf-8")
    except TypeError as e:
        logger.error(f"Error serializing data to JSON: {data_dict}. Error: {e}")
        return None


# --- Kafka Producer Callbacks (Optional but Recommended) ---
def on_send_success(record_metadata):
    """Callback for successful message production."""
    logger.debug(
        f"Message produced successfully: Topic={record_metadata.topic}, "
        f"Partition={record_metadata.partition}, Offset={record_metadata.offset}"
    )


def on_send_error(excp):
    """Callback for errors during message production."""
    logger.error("Error producing message to Kafka", exc_info=excp)


# --- Main Application Logic ---
def cloud_min_worker():
    """
    Main function to run the Kafka consumer, aggregator, and producer.
    """
    consumer = None
    producer = None
    running = True  # Flag to control the main loop for graceful shutdown

    try:
        # --- Initialize Kafka Consumer ---
        consumer = KafkaConsumer(
            *SOURCE_TOPICS,
            bootstrap_servers=KAFKA_BROKER,
            value_deserializer=json_deserializer,
            group_id=CONSUMER_GROUP_ID,
            auto_offset_reset="earliest",
            consumer_timeout_ms=1000,
        )
        logger.info(
            f"Kafka Consumer initialized for topics: {SOURCE_TOPICS}, Group ID: {CONSUMER_GROUP_ID}"
        )

        # --- Initialize Kafka Producer ---
        producer = KafkaProducer(
            bootstrap_servers=KAFKA_BROKER,
            value_serializer=json_serializer,
            retries=3,
            acks="all",
        )
        logger.info(f"Kafka Producer initialized for topic: {TARGET_TOPIC}")

        # --- Data Structures for Aggregation ---
        # Stores aggregated consumption and count:
        # { "YYYY-MM-DDTHH:MM:00+00:00": {"total_consumption": sum, "message_count": count} }
        minute_aggregates = {}

        logger.info("Starting consumption and aggregation loop...")
        while running:
            try:
                for message in consumer:
                    if not running:
                        break

                    logger.debug(
                        f"Received message: Topic={message.topic}, Partition={message.partition}, "
                        f"Offset={message.offset}, Key={message.key}"
                    )

                    data = message.value
                    if data is None:
                        logger.warning(
                            f"Skipping null message from Topic={message.topic}, Offset={message.offset}"
                        )
                        continue

                    if not isinstance(data, dict):
                        logger.warning(f"Skipping invalid message structure: {data}")
                        continue

                    try:
                        msg_timestamp_str = data["timestamp_minute"]
                        consumption_value = data["total_consumption"]

                        if not isinstance(consumption_value, (int, float)):
                            logger.warning(
                                f"Invalid consumption value type: {consumption_value} in message: {data}"
                            )
                            continue

                        msg_dt_original = datetime.fromisoformat(msg_timestamp_str)
                        current_msg_minute_dt = msg_dt_original.astimezone(
                            timezone.utc
                        ).replace(second=0, microsecond=0)
                        current_msg_minute_key_str = current_msg_minute_dt.isoformat(
                            timespec="seconds"
                        )

                        # Initialize aggregate for the minute if it's new
                        if current_msg_minute_key_str not in minute_aggregates:
                            minute_aggregates[current_msg_minute_key_str] = {
                                "total_consumption": 0.0,
                                "message_count": 0,
                            }

                        # Aggregate consumption and increment count
                        minute_aggregates[current_msg_minute_key_str][
                            "total_consumption"
                        ] += float(consumption_value)
                        minute_aggregates[current_msg_minute_key_str][
                            "message_count"
                        ] += 1

                        current_agg_data = minute_aggregates[current_msg_minute_key_str]
                        logger.debug(
                            f"Aggregated for {current_msg_minute_key_str}: "
                            f"Sum={current_agg_data['total_consumption']:.2f}, "
                            f"Count={current_agg_data['message_count']}/{EXPECTED_MESSAGES_PER_MINUTE_INTERVAL}"
                        )

                        # --- New Flushing Logic ---
                        # Flush if the expected number of messages for this minute has been received
                        if (
                            current_agg_data["message_count"]
                            == EXPECTED_MESSAGES_PER_MINUTE_INTERVAL
                        ):
                            output_payload = {
                                "timestamp_minute": current_msg_minute_key_str,
                                "total_consumption": round(
                                    current_agg_data["total_consumption"], 2
                                ),
                            }
                            producer.send(
                                TARGET_TOPIC, value=output_payload
                            ).add_callback(on_send_success).add_errback(on_send_error)
                            logger.info(
                                f"Flushed to {TARGET_TOPIC} (Count reached {EXPECTED_MESSAGES_PER_MINUTE_INTERVAL}): {output_payload}"
                            )
                            del minute_aggregates[
                                current_msg_minute_key_str
                            ]  # Remove after flushing
                            logger.debug(
                                f"Removed aggregate for: {current_msg_minute_key_str} after flushing."
                            )
                        elif (
                            current_agg_data["message_count"]
                            > EXPECTED_MESSAGES_PER_MINUTE_INTERVAL
                        ):
                            # This case should ideally not happen if data is clean and logic is correct,
                            # but it's good to log if it does.
                            logger.warning(
                                f"Minute {current_msg_minute_key_str} has "
                                f"{current_agg_data['message_count']} messages, "
                                f"exceeding expected {EXPECTED_MESSAGES_PER_MINUTE_INTERVAL}. "
                                f"It was likely already flushed."
                            )

                    except ValueError as ve:
                        logger.error(
                            f"ValueError processing message content {data}: {ve}"
                        )
                    except Exception as e_msg_proc:
                        logger.error(
                            f"Unexpected error processing message {message.value}: {e_msg_proc}",
                            exc_info=True,
                        )

                if not running:
                    break

            except StopIteration:
                logger.debug(
                    "Consumer poll timed out. No new messages in this interval."
                )
                if not running:
                    break
                continue

            except KafkaError as ke:
                logger.error(
                    f"Kafka error during consumption loop: {ke}", exc_info=True
                )
                running = False
                break
            except Exception as e_loop:
                logger.error(
                    f"Unexpected error in main consumption loop: {e_loop}",
                    exc_info=True,
                )
                running = False
                break

    except KeyboardInterrupt:
        logger.info("KeyboardInterrupt received. Initiating graceful shutdown...")
        running = False
    except KafkaError as ke_init:
        logger.error(f"Fatal Kafka initialization error: {ke_init}", exc_info=True)
        running = False
    except Exception as e_outer:
        logger.error(f"An unexpected error occurred: {e_outer}", exc_info=True)
        running = False
    finally:
        logger.info("Starting final cleanup and flush of remaining aggregates...")

        if producer and minute_aggregates:
            logger.info(
                f"Flushing {len(minute_aggregates)} remaining aggregate(s) during shutdown..."
            )
            sorted_minute_keys = sorted(minute_aggregates.keys())
            for minute_key_str in sorted_minute_keys:
                agg_data = minute_aggregates[minute_key_str]
                output_payload = {
                    "timestamp_minute": minute_key_str,
                    "total_consumption": round(agg_data["total_consumption"], 2),
                }

                log_message_prefix = f"Final Flush - {minute_key_str}"
                if agg_data["message_count"] < EXPECTED_MESSAGES_PER_MINUTE_INTERVAL:
                    logger.warning(
                        f"{log_message_prefix} has only {agg_data['message_count']}/"
                        f"{EXPECTED_MESSAGES_PER_MINUTE_INTERVAL} messages. Flushing anyway."
                    )
                else:
                    logger.info(
                        f"{log_message_prefix} has {agg_data['message_count']} messages. Flushing."
                    )

                try:
                    producer.send(TARGET_TOPIC, value=output_payload).add_callback(
                        on_send_success
                    ).add_errback(on_send_error)
                    logger.info(
                        f"Final Flush - Sent to {TARGET_TOPIC}: {output_payload}"
                    )
                except Exception as e_final_send:
                    logger.error(
                        f"Error during final flush send for {minute_key_str}: {e_final_send}"
                    )

            try:
                producer.flush(timeout=10)
                logger.info("Producer flushed all pending messages.")
            except KafkaError as e_flush:
                logger.error(f"Error flushing producer during shutdown: {e_flush}")

        if producer:
            producer.close()
            logger.info("Kafka Producer closed.")
        if consumer:
            consumer.close()
            logger.info("Kafka Consumer closed.")

        logger.info("Application shutdown complete.")


if __name__ == "__main__":
    # --- Prerequisites for running this script: ---
    # (Prerequisites remain the same as before)
    # 1. Kafka broker running.
    # 2. Source and target topics created.
    # 3. Data being produced to source topics.
    cloud_min_worker()
