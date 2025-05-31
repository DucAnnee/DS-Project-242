import json
import random
from kafka import KafkaProducer
from datetime import datetime, timezone, timedelta

from utils import meter_partitioner


def parse_args():
    import argparse

    parser = argparse.ArgumentParser(description="Meter Data Sender")
    parser.add_argument(
        "--meter_id",
        type=str,
        default="SM_001",
        help="Unique identifier for the meter",
    )
    parser.add_argument(
        "--topic",
        type=str,
        default="meter_data",
        help="Kafka topic to send data to",
    )
    parser.add_argument(
        "--start", type=float, default=0.0, help="Initial consumption value"
    )
    parser.add_argument(
        "--max_step", type=float, default=0.5, help="Maximum step increase per interval"
    )
    parser.add_argument(
        "--interval",
        type=int,
        default=60,
        help="Interval in seconds between data sends",
    )

    return parser.parse_args()


class Meter:
    def __init__(
        self,
        meter_id: str,
        topic: str,
        start: float = 0.0,
        max_step: float = 0.5,
        interval: int = 60,
    ):
        self.METER_ID = meter_id
        self.TOPIC = topic
        self.INTERVAL = interval  # seconds
        self._current_consumption = start
        self._current_time = datetime(2025, 5, 22, 10, 0, 0, tzinfo=timezone.utc)
        self.max_step = max_step
        self.producer = KafkaProducer(
            bootstrap_servers="192.168.182.128:9092",
            value_serializer=lambda v: json.dumps(v).encode("utf-8"),
            partitioner=meter_partitioner,
        )

    def send_data(self, verbose=True):
        if verbose:
            print(
                f"[{self.get_id()}] Starting to send data to topic '{self.get_topic()}' every {self.get_interval()} seconds..."
            )
        for i in range(1000):
            data = self.get_data()
            self.producer.send(
                self.get_topic(), key=bytes(self.get_id(), "utf-8"), value=data
            )
            self.producer.flush()
            # time.sleep(self.get_interval())
        self.shutdown()

    def get_data(self):
        now_iso = self._current_time.isoformat()

        step = random.uniform(0.01, self.max_step)
        self._current_consumption = round(self._current_consumption + step, 2)
        self._current_time += timedelta(minutes=1)
        data = {
            "meter_id": self.get_id(),
            "timestamp": now_iso,
            "consumption": self._current_consumption,
        }
        return data

    def get_id(self):
        return self.METER_ID

    def get_topic(self):
        return self.TOPIC

    def get_interval(self):
        return self.INTERVAL

    def shutdown(self):
        self.producer.flush()
        self.producer.close()


if __name__ == "__main__":
    meter = Meter(meter_id="01-01-0001", topic="ward1")
    # for i in range(5):
    #     data = meter.get_data()
    #     meter.producer.send(
    #         meter.get_topic(), key=bytes(meter.get_id(), "utf-8"), value=data
    #     )
    # meter.shutdown()
    try:
        meter.send_data()
    except KeyboardInterrupt:
        print("Stopping meter sending...")
