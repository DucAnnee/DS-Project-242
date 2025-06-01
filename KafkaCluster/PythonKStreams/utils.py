import json
import math
import random


class JsonSerializer:
    async def serialize(self, payload, headers, serializer_kwargs):
        return json.dumps(payload).encode()


class JsonDeserializer:
    async def deserialize(self, consumer_record, **kwargs):
        consumer_record.value = json.loads(consumer_record.value.decode())
        return consumer_record


def dict_serializer(data):
    return json.dumps(data).encode("utf-8")


def dict_deserializer(data):
    return json.loads(data.decode("utf-8"))


def is_missing(value):
    return True if (value == None or value == "---" or float(value) == 0) else False


def get_fields(sensor_type):
    if sensor_type == "air":
        return [
            "Temperature",
            "Moisture",
            "Light",
            "Total_Rainfall",
            "Rainfall",
            "Wind_Direction",
            "PM2.5",
            "PM10",
            "CO",
            "NOx",
            "SO2",
        ]
    elif sensor_type == "earth":
        return [
            "Moisture",
            "Temperature",
            "Salinity",
            "pH",
            "Water_Root",
            "Water_Leaf",
            "Water_Level",
            "Voltage",
        ]
    else:
        return ["pH", "DO", "Temperature", "Salinity"]


def get_tracker(sensor_type, field, stats_dict):
    key = (sensor_type, field)
    if key not in stats_dict:
        stats_dict[key] = Tracker()
    return stats_dict[key]


class Tracker:
    def __init__(self):
        self.count = 0
        self.sum = 0.0
        self.sum_sqr = 0.0

    def update(self, value):
        self.count += 1
        self.sum += value
        self.sum_sqr += value**2

    def mean(self):
        return 0.0 if self.count == 0 else self.sum / self.count

    def std(self):
        if self.count < 2:
            return 0.0
        mean_val = self.mean()
        return math.sqrt((self.sum_sqr / self.count) - (mean_val**2))
