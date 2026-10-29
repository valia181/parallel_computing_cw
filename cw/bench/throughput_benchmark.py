import argparse
import csv
import random
import threading
import time
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import requests

URL = "http://localhost:8080"
OUT_DIR = Path(__file__).parent / "results"

QUERIES = ["time", "people", "world", "story", "love", "war", "life", "water", "night", "city"]


def run_level(mode, clients, duration):
    latencies = []  # list.append is thread-safe
    errors = []
    deadline = time.time() + duration

    def client():
        session = requests.Session()
        while time.time() < deadline:
            query = " ".join(random.sample(QUERIES, 2))
            start = time.time()
            try:
                response = session.post(f"{URL}/api/search", json={"query": query, "mode": mode},
                                        timeout=120)
                response.raise_for_status()
                latencies.append(time.time() - start)
            except requests.RequestException:
                errors.append(1)

    started = time.time()
    threads = [threading.Thread(target=client) for _ in range(clients)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.time() - started

    throughput = len(latencies) / elapsed
    avg_latency_ms = 1000 * sum(latencies) / len(latencies) if latencies else 0
    return throughput, avg_latency_ms, len(errors)


def check_server(mode):
    health = requests.get(f"{URL}/api/health", timeout=10).json()
    if mode == "ollama" and not health.get("ollama"):
        raise SystemExit("Ollama is not available: mode=ollama would fall back to keyword search.")

    found = 0
    for query in QUERIES:
        response = requests.post(f"{URL}/api/search", json={"query": query, "mode": "keyword"},
                                 timeout=120)
        found += len(response.json()["results"])
    if found == 0:
        raise SystemExit("No query returned results: build the index or edit QUERIES.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["keyword", "ollama"], default="keyword")
    parser.add_argument("--duration", type=int, default=10, help="seconds per level")
    parser.add_argument("--clients", default="1,2,4,8,16,32")
    args = parser.parse_args()
    client_counts = [int(c) for c in args.clients.split(",")]

    check_server(args.mode)
    print(f"mode={args.mode}, {args.duration} s per level\n")
    print(f"{'clients':>8} {'req/s':>10} {'avg ms':>10} {'errors':>7}")

    results = []
    for clients in client_counts:
        throughput, latency, errors = run_level(args.mode, clients, args.duration)
        results.append({"clients": clients, "throughput_rps": throughput,
                        "avg_latency_ms": latency, "errors": errors})
        print(f"{clients:>8} {throughput:>10.2f} {latency:>10.1f} {errors:>7}")

    OUT_DIR.mkdir(exist_ok=True)
    name = f"throughput_{args.mode}"
    with (OUT_DIR / f"{name}.csv").open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["clients", "throughput_rps", "avg_latency_ms",
                                               "errors"])
        writer.writeheader()
        writer.writerows(results)

    xs = [r["clients"] for r in results]
    fig, axs = plt.subplots(1, 2, figsize=(11, 4.5))

    axs[0].plot(xs, [r["throughput_rps"] for r in results], marker="o", color="b")
    axs[0].set_title(f"Throughput vs clients (mode={args.mode})")
    axs[0].set_ylabel("Requests per second")

    axs[1].plot(xs, [r["avg_latency_ms"] for r in results], marker="s", color="r")
    axs[1].set_title(f"Average latency vs clients (mode={args.mode})")
    axs[1].set_ylabel("Latency (ms)")

    for ax in axs:
        ax.set_xlabel("Concurrent clients")
        ax.grid(True)

    plt.tight_layout()
    plt.savefig(OUT_DIR / f"{name}.png", dpi=150)
    print(f"\nSaved to {OUT_DIR}")


if __name__ == "__main__":
    main()
