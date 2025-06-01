import json
from kafka import KafkaConsumer, KafkaProducer
from kafka.errors import KafkaError
from datetime import datetime, timezone, timedelta
import time
import logging

# --- Configuration ---
KAFKA_BROKER = "localhost:9092"
SOURCE_TOPIC = "district1.1m.sum"  # Input: minute-level sums
TARGET_TOPIC = "district1.1h.sum"  # Output: hour-level sums
# Use a unique group_id for your consumer group
CONSUMER_GROUP_ID = "district-hour-aggregator-group-py-v3"  # Updated for new logic
EXPECTED_MESSAGES_PER_HOUR_INTERVAL = 60  # Flush after 60 messages (minutes)
AGGREGATE_TIMEOUT_MINUTES = 60  # Flush if open for 60 minutes of processing time

# --- Logging Setup ---
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(levelname)s - %(module)s - %(message)s",
    handlers=[logging.StreamHandler()],
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


# --- Kafka Producer Callbacks ---
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
def hour_worker():
    """
    Main function to run the Kafka consumer, hourly aggregator, and producer.
    """
    consumer = None
    producer = None
    running = True

    try:
        # --- Initialize Kafka Consumer ---
        consumer = KafkaConsumer(
            SOURCE_TOPIC,
            bootstrap_servers=KAFKA_BROKER,
            value_deserializer=json_deserializer,
            group_id=CONSUMER_GROUP_ID,
            auto_offset_reset="latest",
            consumer_timeout_ms=1000,  # Unblocks loop to allow timeout checks
        )
        logger.info(
            f"Kafka Consumer initialized for topic: {SOURCE_TOPIC}, Group ID: {CONSUMER_GROUP_ID}"
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
        # Stores aggregated consumption, count, and processing start time:
        # { "YYYY-MM-DDTHH:00:00+00:00": {
        #       "total_consumption": sum,
        #       "message_count": count,
        #       "processing_start_time_utc": datetime_object
        #   }
        # }
        hour_aggregates = {}

        logger.info("Starting consumption and hourly aggregation loop...")
        while running:
            try:
                for message in consumer:  # This loop blocks until a message or timeout
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

                    if (
                        not isinstance(data, dict)
                        or "timestamp_minute" not in data
                        or "total_consumption" not in data
                    ):
                        logger.warning(
                            f"Skipping invalid message structure from minute aggregator: {data}"
                        )
                        continue

                    try:
                        minute_timestamp_str = data["timestamp_minute"]
                        minute_total_consumption = data["total_consumption"]

                        if not isinstance(minute_total_consumption, (int, float)):
                            logger.warning(
                                f"Invalid total_consumption value type: {minute_total_consumption} in message: {data}"
                            )
                            continue

                        minute_dt_original = datetime.fromisoformat(
                            minute_timestamp_str
                        )
                        current_msg_hour_dt = minute_dt_original.astimezone(
                            timezone.utc
                        ).replace(minute=0, second=0, microsecond=0)
                        current_msg_hour_key_str = current_msg_hour_dt.isoformat(
                            timespec="seconds"
                        )

                        # Initialize or update aggregate for the hour
                        if current_msg_hour_key_str not in hour_aggregates:
                            hour_aggregates[current_msg_hour_key_str] = {
                                "total_consumption": 0.0,
                                "message_count": 0,
                                "processing_start_time_utc": datetime.now(
                                    timezone.utc
                                ),  # Mark processing start
                            }

                        hour_aggregates[current_msg_hour_key_str][
                            "total_consumption"
                        ] += float(minute_total_consumption)
                        hour_aggregates[current_msg_hour_key_str]["message_count"] += 1

                        current_agg_data = hour_aggregates[current_msg_hour_key_str]
                        logger.debug(
                            f"Aggregated for hour {current_msg_hour_key_str}: "
                            f"Sum={current_agg_data['total_consumption']:.2f}, "
                            f"Count={current_agg_data['message_count']}/"
                            f"{EXPECTED_MESSAGES_PER_HOUR_INTERVAL}, "
                            f"Open since={current_agg_data['processing_start_time_utc'].isoformat()}"
                        )

                        # --- Count-based Flushing Logic ---
                        if (
                            current_agg_data["message_count"]
                            >= EXPECTED_MESSAGES_PER_HOUR_INTERVAL
                        ):
                            output_payload = {
                                "timestamp_hour": current_msg_hour_key_str,
                                "hourly_total_consumption": round(
                                    current_agg_data["total_consumption"], 2
                                ),
                                "reason_for_flush": "count_reached",
                            }
                            producer.send(
                                TARGET_TOPIC, value=output_payload
                            ).add_callback(on_send_success).add_errback(on_send_error)
                            logger.info(
                                f"Flushed to {TARGET_TOPIC} (Count reached {EXPECTED_MESSAGES_PER_HOUR_INTERVAL}): {output_payload}"
                            )
                            del hour_aggregates[current_msg_hour_key_str]
                            logger.debug(
                                f"Removed aggregate for hour: {current_msg_hour_key_str} after count-based flush."
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

                if not running:  # Check after inner loop if shutdown initiated
                    break

            except StopIteration:  # Triggered by consumer_timeout_ms
                logger.debug(
                    "Consumer poll timed out. Checking for aggregate timeouts..."
                )
                # --- Timeout-based Flushing Logic ---
                now_utc = datetime.now(timezone.utc)
                keys_to_flush_on_timeout = []
                for hour_key_str, agg_data in list(
                    hour_aggregates.items()
                ):  # Iterate on a copy
                    processing_duration = (
                        now_utc - agg_data["processing_start_time_utc"]
                    )
                    if processing_duration >= timedelta(
                        minutes=AGGREGATE_TIMEOUT_MINUTES
                    ):
                        logger.info(
                            f"Aggregate for hour {hour_key_str} timed out. "
                            f"Open for {processing_duration}. Count: {agg_data['message_count']}."
                        )
                        output_payload = {
                            "timestamp_hour": hour_key_str,
                            "hourly_total_consumption": round(
                                agg_data["total_consumption"], 2
                            ),
                            "reason_for_flush": "timeout",
                        }
                        producer.send(TARGET_TOPIC, value=output_payload).add_callback(
                            on_send_success
                        ).add_errback(on_send_error)
                        logger.info(
                            f"Flushed to {TARGET_TOPIC} (Timeout): {output_payload}"
                        )
                        keys_to_flush_on_timeout.append(hour_key_str)

                for key_to_remove in keys_to_flush_on_timeout:
                    if key_to_remove in hour_aggregates:
                        del hour_aggregates[key_to_remove]
                        logger.debug(
                            f"Removed aggregate for hour: {key_to_remove} after timeout-based flush."
                        )

                if (
                    not running
                ):  # Check if shutdown was requested during timeout processing
                    break
                continue  # Continue to the next iteration of the 'while running' loop.

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
        logger.info(
            "Starting final cleanup and flush of remaining hourly aggregates..."
        )

        if producer and hour_aggregates:
            logger.info(
                f"Flushing {len(hour_aggregates)} remaining hourly aggregate(s) during shutdown..."
            )
            sorted_hour_keys = sorted(
                hour_aggregates.keys()
            )  # Sort for consistent logging
            for hour_key_str in sorted_hour_keys:
                agg_data = hour_aggregates[hour_key_str]
                output_payload = {
                    "timestamp_hour": hour_key_str,
                    "hourly_total_consumption": round(agg_data["total_consumption"], 2),
                    "reason_for_flush": "shutdown",
                    "message_count_at_shutdown": agg_data["message_count"],
                }
                logger.info(
                    f"Final Flush for {hour_key_str} (Count: {agg_data['message_count']}): {output_payload}"
                )
                try:
                    producer.send(TARGET_TOPIC, value=output_payload).add_callback(
                        on_send_success
                    ).add_errback(on_send_error)
                except Exception as e_final_send:
                    logger.error(
                        f"Error during final flush send for hour {hour_key_str}: {e_final_send}"
                    )

            try:
                producer.flush(timeout=10)
                logger.info(
                    "Producer flushed all pending messages for hourly aggregates."
                )
            except KafkaError as e_flush:
                logger.error(
                    f"Error flushing producer during shutdown (hourly): {e_flush}"
                )

        if producer:
            producer.close()
            logger.info("Kafka Producer (Hourly Aggregator) closed.")
        if consumer:
            consumer.close()
            logger.info("Kafka Consumer (Hourly Aggregator) closed.")

        logger.info("Hourly Aggregator application shutdown complete.")


if __name__ == "__main__":
    # --- Prerequisites for running this script: ---
    # 1. A Kafka broker running at KAFKA_BROKER.
    # 2. The source topic 'district.1m.sum' must exist and have data produced to it
    #    by the minute aggregator script.
    # 3. The target topic 'district.1h.sum' must exist (or auto-creation enabled).
    hour_worker()
