from meter import Meter
from argparse import ArgumentParser
from threading import Thread
import datetime

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
    start_time = datetime.datetime.now()
    print(f"Script started at: {start_time.strftime('%Y-%m-%d %H:%M:%S.%f')[:-2]}")
    num_meters = 5
    wards = {"ward1": "01", "ward2": "02", "ward3": "03"}
    district = {"district1": "01", "district2": "02", "district3": "03"}

    args = parse_arg()

    if args.district:
        district = {args.district: district[args.district]}

    threads = []
    for d in district.keys():
        for w in wards.keys():
            for i in range(num_meters):
                # print(f"{district[d]}-{wards[w]}-000{i+1}")
                meter = Meter(f"{district[d]}-{wards[w]}-000{i+1}", w)
                thread = Thread(target=meter.send_data)
                threads.append(thread)
                thread.start()
    
    for i in range(num_meters):
        threads[i].join()
        
    print("im done, kms")


if __name__ == "__main__":
    main()
