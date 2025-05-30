def meter_partitioner(key_bytes, all_partitions, available_partitions):
    key_str = key_bytes.decode("utf-8")
    suffix = key_str[-4:]
    try:
        num = int(suffix)
    except ValueError:
        num = sum(ord(c) for c in key_str)
    idx = num % len(all_partitions)
    # print("Partition index: ", idx)
    return all_partitions[idx]
