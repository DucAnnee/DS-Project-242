from meter import Meter
from argparse import ArgumentParser
from threading import Thread
from datetime import datetime


def parse_arg():
    parser = ArgumentParser(
        prog="Meter Data Sending Simulation",
        description="Simulate sending meter data to Kafka",
    )

    parser.add_argument(
        "-d",
        "--district",
        type=str,
        required=False,
        help="District name, e.g., district1, district2, etc.",
    )

    return parser.parse_args()


def main():
    num_meters = 5
    wards = {"ward1": "01", "ward2": "02", "ward3": "03"}
    district = {"district1": "01", "district2": "02"}

    args = parse_arg()

    if args.district:
        district = {args.district: district[args.district]}

    threads = []
    print(datetime.now())
    for d in district.keys():
        for w in wards.keys():
            for i in range(num_meters):
                # print(f"{district[d]}-{wards[w]}-000{i+1}", "----", f"fog{d[-1]}")

                meter = Meter(f"{district[d]}-{wards[w]}-000{i+1}", w, [f"fog{d[-1]}"])
                thread = Thread(target=meter.send_data)
                threads.append(thread)
                thread.start()

    [t.join() for t in threads]


if __name__ == "__main__":
    main()
