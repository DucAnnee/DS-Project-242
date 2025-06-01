from cloudHourAgg import cloud_hour_worker
from cloudMinAgg import cloud_min_worker
from cloudPeak import cloud_peak_worker
from threading import Thread


def main():
    threads = list()
    threads.append(Thread(target=cloud_hour_worker))
    threads.append(Thread(target=cloud_min_worker))
    threads.append(Thread(target=cloud_peak_worker))

    [t.start() for t in threads]
    [t.join() for t in threads]

    print("cloud_run.py is done, exitting")


if __name__ == "__main__":
    main()
