import json
import sys

import requests
from websocket import create_connection

BASE = "http://localhost:8080"
failed = 0


def check(name, condition):
    global failed
    print(("PASS  " if condition else "FAIL  ") + name)
    if not condition:
        failed += 1


def post_search(body):
    return requests.post(f"{BASE}/api/search", json=body, timeout=120)


response = requests.get(f"{BASE}/api/health", timeout=10)
body = response.json()
check("GET /api/health returns 200", response.status_code == 200)
check("health has status=ok and boolean 'ollama'",
      body.get("status") == "ok" and isinstance(body.get("ollama"), bool))

if "--build" in sys.argv:
    response = requests.post(f"{BASE}/api/index", json={"threads": 4}, timeout=3600)
    body = response.json()
    check("POST /api/index returns 200", response.status_code == 200)
    check("index response has status, documents_indexed, time_ms",
          {"status", "documents_indexed", "time_ms"} <= body.keys())

response = requests.post(f"{BASE}/api/index", json={"threads": 0}, timeout=60)
check("POST /api/index with threads=0 returns 400", response.status_code == 400)

body = requests.get(f"{BASE}/api/stats", timeout=10).json()
check("stats has documents, index_build_time_ms, threads_used",
      {"documents", "index_build_time_ms", "threads_used"} <= body.keys())
check("index is built (documents > 0)", body.get("documents", 0) > 0)

response = post_search({"query": "time", "mode": "keyword"})
body = response.json()
check("POST /api/search returns 200", response.status_code == 200)
check("search response has results and time_ms", {"results", "time_ms"} <= body.keys())
items = body.get("results", [])
check("every result has doc_id, score, snippet",
      all({"doc_id", "score", "snippet"} <= item.keys() for item in items))
check("results are sorted by score (descending)",
      all(a["score"] >= b["score"] for a, b in zip(items, items[1:])))
check("missing query returns 400", post_search({"mode": "keyword"}).status_code == 400)
check("unknown mode returns 400", post_search({"query": "time", "mode": "x"}).status_code == 400)
check("query without letters returns 400",
      post_search({"query": "123", "mode": "keyword"}).status_code == 400)

ws = create_connection("ws://localhost:8080/ws/search", timeout=120)
ws.send(json.dumps({"query": "time"}))
stages = []
while True:
    message = json.loads(ws.recv())
    stages.append(message["stage"])
    if message["stage"] in ("done", "error"):
        break
ws.close()
check("WebSocket stages: query_expansion -> keyword_search -> done",
      stages == ["query_expansion", "keyword_search", "done"])

print(f"\n{'ALL CHECKS PASSED' if failed == 0 else str(failed) + ' CHECK(S) FAILED'}")
sys.exit(1 if failed else 0)
