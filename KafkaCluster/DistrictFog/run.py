from hourAgg import hour_worker
from minAgg import min_worker
from peak import peak_worker
from prod import cloud_prod_worker
from threading import Thread


def main():
    threads = list()
    threads.append(Thread(target=hour_worker))
    threads.append(Thread(target=min_worker))
    threads.append(Thread(target=peak_worker))
    threads.append(Thread(target=cloud_prod_worker))

    [t.start() for t in threads]
    [t.join() for t in threads]

    print("run.py is done, exitting")


if __name__ == "__main__":
    main()
