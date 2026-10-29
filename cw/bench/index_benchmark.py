import csv
import multiprocessing
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import requests

URL = "http://localhost:8080/api/index"
RUNS_PER_THREAD = 3
OUT_DIR = Path(__file__).parent / "results"


def get_thread_counts():
    max_cores = multiprocessing.cpu_count()
    counts = []
    t = 1
    while t <= max_cores:
        counts.append(t)
        t *= 2
    if max_cores not in counts:
        counts.append(max_cores)
    return counts


def build_index(threads):
    response = requests.post(URL, json={"threads": threads})
    response.raise_for_status()
    return response.json()["time_ms"]


def main():
    thread_counts = get_thread_counts()
    print(f"Logical cores: {multiprocessing.cpu_count()}")
    print(f"Thread counts to test: {thread_counts}\n")

    print("Warm-up run...", end=" ", flush=True)
    print(f"{build_index(thread_counts[-1])} ms\n")

    results = []
    for threads in thread_counts:
        print(f"Threads: {threads}")
        times = []
        for run in range(1, RUNS_PER_THREAD + 1):
            time_ms = build_index(threads)
            print(f"  run {run}/{RUNS_PER_THREAD}: {time_ms} ms")
            times.append(time_ms)

        avg_time = sum(times) / len(times)
        t1_time = results[0]["time_ms"] if results else avg_time
        speedup = t1_time / avg_time
        results.append({"threads": threads, "time_ms": avg_time,
                        "speedup": speedup, "efficiency": speedup / threads})
        print(f"  average: {avg_time:.0f} ms | speedup: {speedup:.2f}x | "
              f"efficiency: {speedup / threads:.2f}\n")

    OUT_DIR.mkdir(exist_ok=True)
    with (OUT_DIR / "index_benchmark.csv").open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["threads", "time_ms", "speedup", "efficiency"])
        writer.writeheader()
        writer.writerows(results)

    threads_list = [r["threads"] for r in results]
    fig, axs = plt.subplots(1, 3, figsize=(15, 5))

    axs[0].plot(threads_list, [r["time_ms"] for r in results], marker="o", color="b")
    axs[0].set_title("Build time vs threads")
    axs[0].set_ylabel("Time (ms)")

    axs[1].plot(threads_list, [r["speedup"] for r in results], marker="s", color="g",
                label="Measured")
    axs[1].plot(threads_list, threads_list, linestyle="--", color="gray", label="Ideal")
    axs[1].set_title("Speedup")
    axs[1].set_ylabel("S = T1 / Tp")
    axs[1].legend()

    axs[2].plot(threads_list, [r["efficiency"] for r in results], marker="^", color="r")
    axs[2].axhline(y=1.0, linestyle="--", color="gray", label="Ideal")
    axs[2].set_title("Efficiency")
    axs[2].set_ylabel("E = S / p")
    axs[2].legend()

    for ax in axs:
        ax.set_xlabel("Threads")
        ax.grid(True)

    plt.tight_layout()
    plt.savefig(OUT_DIR / "index_benchmark.png", dpi=150)
    print(f"Saved to {OUT_DIR}")


if __name__ == "__main__":
    main()
