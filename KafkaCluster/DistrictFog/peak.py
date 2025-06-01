import json
from kafka import KafkaConsumer, KafkaProducer
from kafka.errors import KafkaError
from datetime import datetime, timezone, timedelta
import time
import logging

# --- Configuration ---
KAFKA_BROKER = "localhost:9092"
# Read from the raw household data topics
SOURCE_TOPICS = ["ward1", "ward2", "ward3"]
TARGET_TOPIC = "district1.1h.peak"  # Output: hour-level peak from a single household
# Use a unique group_id for your consumer group
CONSUMER_GROUP_ID = "district-hourly-household-peak-detector-group-py-v2"

# Assuming 3 wards, 5 houses/ward, 1 message/minute/house:
# 3 wards * 5 houses/ward * 60 minutes/hour = 900 messages per hour
EXPECTED_MESSAGES_PER_HOUR_INTERVAL = 900
# Timeout for an hourly window if not all messages arrive.
# Set slightly higher than 60 to allow for potential delays.
AGGREGATE_TIMEOUT_MINUTES = 65

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
        f"Household peak data produced successfully: Topic={record_metadata.topic}, "
        f"Partition={record_metadata.partition}, Offset={record_metadata.offset}"
    )


def on_send_error(excp):
    """Callback for errors during message production."""
    logger.error("Error producing household peak data to Kafka", exc_info=excp)


# --- Main Application Logic ---
def peak_worker():
    """
    Main function to run the Kafka consumer, household peak detector, and producer.
    """
    consumer = None
    producer = None
    running = True

    try:
        # --- Initialize Kafka Consumer ---
        consumer = KafkaConsumer(
            *SOURCE_TOPICS,  # Unpack the list of source topics
            bootstrap_servers=KAFKA_BROKER,
            value_deserializer=json_deserializer,
            group_id=CONSUMER_GROUP_ID,
            auto_offset_reset="latest",
            consumer_timeout_ms=1000,  # Unblocks loop for timeout checks
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

        # --- Data Structures for Peak Tracking ---
        # { "YYYY-MM-DDTHH:00:00+00:00": { # Key is the hour string
        #       "peak_household_consumption": float_value,
        #       "peak_meter_id": "string_meter_id",
        #       "peak_timestamp": "YYYY-MM-DDTHH:MM:SS+00:00", # Exact timestamp of the peak household reading
        #       "message_count": int_count, # Count of raw household messages for this hour
        #       "processing_start_time_utc": datetime_object # When this hour's tracking started
        #   }
        # }
        hourly_household_peak_tracker = {}

        logger.info("Starting consumption and household peak detection loop...")
        while running:
            try:
                for message in consumer:  # This loop blocks until a message or timeout
                    if not running:
                        break

                    logger.debug(
                        f"Received household message for peak detection: Topic={message.topic}, "
                        f"Partition={message.partition}, Offset={message.offset}"
                    )

                    # Expected raw household data: {"meter_id": "...", "timestamp": "...", "consumption": ...}
                    data = message.value
                    if data is None:
                        logger.warning(
                            f"Skipping null message from Topic={message.topic}, Offset={message.offset}"
                        )
                        continue

                    if (
                        not isinstance(data, dict)
                        or "meter_id" not in data
                        or "timestamp" not in data
                        or "consumption" not in data
                    ):
                        logger.warning(
                            f"Skipping invalid raw household message structure: {data}"
                        )
                        continue

                    try:
                        raw_meter_id = data["meter_id"]
                        raw_timestamp_str = data[
                            "timestamp"
                        ]  # This is the exact timestamp from the meter
                        raw_consumption = data["consumption"]

                        if not isinstance(raw_consumption, (int, float)):
                            logger.warning(
                                f"Invalid consumption value type: {raw_consumption} in message: {data}"
                            )
                            continue

                        raw_event_dt_original = datetime.fromisoformat(
                            raw_timestamp_str
                        )
                        # Determine the hour window for this message
                        event_hour_dt = raw_event_dt_original.astimezone(
                            timezone.utc
                        ).replace(minute=0, second=0, microsecond=0)
                        event_hour_key_str = event_hour_dt.isoformat(timespec="seconds")

                        # Initialize or update peak tracking for the hour
                        if event_hour_key_str not in hourly_household_peak_tracker:
                            hourly_household_peak_tracker[event_hour_key_str] = {
                                "peak_household_consumption": float(
                                    "-inf"
                                ),  # Initialize with negative infinity
                                "peak_meter_id": None,
                                "peak_timestamp": None,
                                "message_count": 0,
                                "processing_start_time_utc": datetime.now(timezone.utc),
                            }

                        tracker_entry = hourly_household_peak_tracker[
                            event_hour_key_str
                        ]
                        tracker_entry["message_count"] += 1

                        # Update peak if current household consumption is higher
                        if (
                            float(raw_consumption)
                            > tracker_entry["peak_household_consumption"]
                        ):
                            tracker_entry["peak_household_consumption"] = float(
                                raw_consumption
                            )
                            tracker_entry["peak_meter_id"] = raw_meter_id
                            tracker_entry["peak_timestamp"] = (
                                raw_timestamp_str  # Store the exact timestamp
                            )
                            logger.info(
                                f"New household peak for hour {event_hour_key_str}: "
                                f"{tracker_entry['peak_household_consumption']:.2f} "
                                f"by meter {tracker_entry['peak_meter_id']} "
                                f"at {tracker_entry['peak_timestamp']} "
                                f"(Msg count: {tracker_entry['message_count']})"
                            )

                        logger.debug(
                            f"Processed household message for hour {event_hour_key_str}: "
                            f"Current Peak={tracker_entry['peak_household_consumption']:.2f}, "
                            f"Meter={tracker_entry['peak_meter_id']}, "
                            f"Count={tracker_entry['message_count']}/"
                            f"{EXPECTED_MESSAGES_PER_HOUR_INTERVAL}, "
                            f"Open since={tracker_entry['processing_start_time_utc'].isoformat()}"
                        )

                        # --- Count-based Flushing Logic ---
                        if (
                            tracker_entry["message_count"]
                            >= EXPECTED_MESSAGES_PER_HOUR_INTERVAL
                        ):
                            if (
                                tracker_entry["peak_meter_id"] is not None
                            ):  # Ensure a peak was actually found
                                output_payload = {
                                    "timestamp_hour": event_hour_key_str,
                                    "peak_meter_id": tracker_entry["peak_meter_id"],
                                    "peak_timestamp": tracker_entry["peak_timestamp"],
                                    "peak_household_consumption": round(
                                        tracker_entry["peak_household_consumption"], 2
                                    ),
                                    "reason_for_flush": "count_reached",
                                    "messages_processed_for_hour": tracker_entry[
                                        "message_count"
                                    ],
                                }
                                producer.send(
                                    TARGET_TOPIC, value=output_payload
                                ).add_callback(on_send_success).add_errback(
                                    on_send_error
                                )
                                logger.info(
                                    f"Flushed household peak to {TARGET_TOPIC} (Count reached): {output_payload}"
                                )
                            else:
                                logger.warning(
                                    f"Count reached for hour {event_hour_key_str} but no household peak recorded."
                                )

                            del hourly_household_peak_tracker[event_hour_key_str]
                            logger.debug(
                                f"Removed household peak tracker for hour: {event_hour_key_str} after count-based flush."
                            )

                    except ValueError as ve:  # For float conversion or datetime parsing
                        logger.error(
                            f"ValueError processing raw household message content {data}: {ve}"
                        )
                    except Exception as e_msg_proc:
                        logger.error(
                            f"Unexpected error processing raw household message {message.value}: {e_msg_proc}",
                            exc_info=True,
                        )

                if not running:
                    break

            except StopIteration:  # Triggered by consumer_timeout_ms
                logger.debug(
                    "Consumer poll timed out. Checking for household peak tracker timeouts..."
                )
                # --- Timeout-based Flushing Logic ---
                now_utc = datetime.now(timezone.utc)
                keys_to_flush_on_timeout = []
                for hour_key_str, tracker_data in list(
                    hourly_household_peak_tracker.items()
                ):  # Iterate on a copy
                    processing_duration = (
                        now_utc - tracker_data["processing_start_time_utc"]
                    )
                    if processing_duration >= timedelta(
                        minutes=AGGREGATE_TIMEOUT_MINUTES
                    ):
                        logger.info(
                            f"Household peak tracker for hour {hour_key_str} timed out. "
                            f"Open for {processing_duration}. Count: {tracker_data['message_count']}."
                        )
                        if tracker_data["peak_meter_id"] is not None:
                            output_payload = {
                                "timestamp_hour": hour_key_str,
                                "peak_meter_id": tracker_data["peak_meter_id"],
                                "peak_timestamp": tracker_data["peak_timestamp"],
                                "peak_household_consumption": round(
                                    tracker_data["peak_household_consumption"], 2
                                ),
                                "reason_for_flush": "timeout",
                                "messages_processed_for_hour": tracker_data[
                                    "message_count"
                                ],
                            }
                            producer.send(
                                TARGET_TOPIC, value=output_payload
                            ).add_callback(on_send_success).add_errback(on_send_error)
                            logger.info(
                                f"Flushed household peak to {TARGET_TOPIC} (Timeout): {output_payload}"
                            )
                        else:
                            logger.warning(
                                f"Timeout for hour {hour_key_str} but no household peak recorded."
                            )
                        keys_to_flush_on_timeout.append(hour_key_str)

                for key_to_remove in keys_to_flush_on_timeout:
                    if key_to_remove in hourly_household_peak_tracker:
                        del hourly_household_peak_tracker[key_to_remove]
                        logger.debug(
                            f"Removed household peak tracker for hour: {key_to_remove} after timeout-based flush."
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
        logger.info(
            "Starting final cleanup and flush of remaining household peak trackers..."
        )

        if producer and hourly_household_peak_tracker:
            logger.info(
                f"Flushing {len(hourly_household_peak_tracker)} remaining household peak tracker(s) during shutdown..."
            )
            sorted_hour_keys = sorted(hourly_household_peak_tracker.keys())
            for hour_key_str in sorted_hour_keys:
                tracker_data = hourly_household_peak_tracker[hour_key_str]
                if tracker_data["peak_meter_id"] is not None:
                    output_payload = {
                        "timestamp_hour": hour_key_str,
                        "peak_meter_id": tracker_data["peak_meter_id"],
                        "peak_timestamp": tracker_data["peak_timestamp"],
                        "peak_household_consumption": round(
                            tracker_data["peak_household_consumption"], 2
                        ),
                        "reason_for_flush": "shutdown",
                        "messages_processed_for_hour": tracker_data["message_count"],
                    }
                    logger.info(
                        f"Final Flush for household peak in hour {hour_key_str} (Count: {tracker_data['message_count']}): {output_payload}"
                    )
                    try:
                        producer.send(TARGET_TOPIC, value=output_payload).add_callback(
                            on_send_success
                        ).add_errback(on_send_error)
                    except Exception as e_final_send:
                        logger.error(
                            f"Error during final flush of household peak for hour {hour_key_str}: {e_final_send}"
                        )
                else:
                    logger.info(
                        f"Final Flush for hour {hour_key_str}: No household peak recorded (Count: {tracker_data['message_count']}). Not sending."
                    )

            try:
                producer.flush(timeout=10)
                logger.info(
                    "Producer flushed all pending household peak data messages."
                )
            except KafkaError as e_flush:
                logger.error(
                    f"Error flushing producer during shutdown (household peak detector): {e_flush}"
                )

        if producer:
            producer.close()
            logger.info("Kafka Producer (Household Peak Detector) closed.")
        if consumer:
            consumer.close()
            logger.info("Kafka Consumer (Household Peak Detector) closed.")

        logger.info("Household Peak Detector application shutdown complete.")


if __name__ == "__main__":
    # --- Prerequisites for running this script: ---
    # 1. Kafka broker running.
    # 2. Source topics 'ward1', 'ward2', 'ward3' exist and have raw household data.
    #    Example data: {"meter_id": "02-01-0001", "timestamp": "2025-05-22T11:03:15+00:00", "consumption": 10.5}
    # 3. Target topic 'district.1h.household_peak' exists (or auto-creation enabled).
    #    Example:
    #    kafka-topics.sh --create --topic district.1h.household_peak --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1
    peak_worker()
