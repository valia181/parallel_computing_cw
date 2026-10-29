import argparse
import csv
import re
from collections import Counter
from pathlib import Path

import requests

URL = "http://localhost:8080"
TOP = 3

QUERIES = ["time", "love story", "city night war", "qzxwvkj"]


def tokenize(text):
    return re.findall(r"[a-z]+", text.lower())


def expected_results(queries, data_dir):
    terms = {q: set(tokenize(q)) for q in queries}
    wanted = set().union(*terms.values())
    scores = {q: {} for q in queries}

    for path in Path(data_dir).rglob("*"):
        if not path.is_file():
            continue
        counts = Counter(w for w in tokenize(path.read_text(encoding="latin-1")) if w in wanted)
        for q in queries:
            score = sum(counts[t] for t in terms[q])
            if score > 0:
                scores[q][path.name] = score

    return {q: sorted(scores[q].items(), key=lambda item: (-item[1], item[0]))[:TOP]
            for q in queries}


def actual_results(query, mode):
    response = requests.post(f"{URL}/api/search", json={"query": query, "mode": mode}, timeout=120)
    response.raise_for_status()
    return [(r["doc_id"], r["score"]) for r in response.json()["results"]][:TOP]


def fmt(results):
    return ", ".join(f"{doc} ({score:g})" for doc, score in results) or "(no results)"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-dir", default="data")
    args = parser.parse_args()

    print(f"Reading dataset in {args.data_dir} (one pass)...")
    expected = expected_results(QUERIES, args.data_dir)

    rows = []
    for query in QUERIES:
        actual = actual_results(query, "keyword")
        passed = [d for d, _ in actual] == [d for d, _ in expected[query]] and all(
            abs(a[1] - e[1]) < 1e-9 for a, e in zip(actual, expected[query]))
        rows.append((f"keyword: {query}", fmt(expected[query]), fmt(actual),
                     "PASS" if passed else "FAIL"))

    health = requests.get(f"{URL}/api/health", timeout=10).json()
    if health.get("ollama"):
        query = QUERIES[0]
        keyword_top = actual_results(query, "keyword")
        ollama_top = actual_results(query, "ollama")
        passed = bool(ollama_top) and ollama_top[0][1] >= keyword_top[0][1]
        rows.append((f"ollama: {query}", "top score >= keyword top score",
                     fmt(ollama_top), "PASS" if passed else "FAIL"))
    else:
        rows.append(("ollama: " + QUERIES[0], "top score >= keyword top score",
                     "Ollama is not available", "SKIP"))

    out_file = Path(__file__).parent / "results" / "search_tests.csv"
    out_file.parent.mkdir(exist_ok=True)
    with out_file.open("w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["query", "expected", "actual", "result"])
        writer.writerows(rows)

    print("\n| Query | Expected | Actual | Result |\n|---|---|---|---|")
    for row in rows:
        print("| " + " | ".join(row) + " |")
    print(f"\nSaved to {out_file}")


if __name__ == "__main__":
    main()
