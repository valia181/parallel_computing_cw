# course_work_parallel_computing

Course work for "Parallel Computing": **a search engine with a parallel inverted-index build and LLM-based query expansion (Ollama)**.

The backend is written in Java 17 (Javalin 6, Jackson). The index is built by a custom thread pool into a custom thread-safe hash table. Queries can be expanded with synonyms from a local Ollama model. Results are served over REST and WebSocket. There are two clients: a console client (CLI) and a web UI.

## Contents

1. [Quick start](#1-quick-start)
2. [Architecture](#2-architecture)
3. [Dataset](#3-dataset)
4. [REST API](#4-rest-api)
5. [WebSocket API](#5-websocket-api)
6. [CLI client](#6-cli-client)
7. [Web UI](#7-web-ui)
8. [Configuration](#8-configuration)
9. [Building and running without Docker](#9-building-and-running-without-docker)
10. [Testing](#10-testing)
11. [Prompts used](#11-prompts-used)

---

## 1. Quick start

Only Docker and Docker Compose are required.

> **Dataset first.** The repository does not ship a dataset. Before building the index, put text files into `./data`: either **generate** a dataset with `generate_dataset.py` or **add your own** files. Both options are described in [section 3 (Dataset)](#3-dataset). After adding or changing files in `./data`, run `docker compose restart search-server` and rebuild the index.

```bash
git clone https://github.com/valia181/parallel_computing_cw.git
cd parallel_computing_cw/cw
docker compose up --build
```

Compose starts two separate services:

| Service | Container | Port | Purpose |
|---|---|---|---|
| `ollama` | `ollama` | 11434 | LLM server. On startup it pulls the `llama3.2:1b` model by itself (the model is stored in the `ollama_data` volume, so it is downloaded only on the first run) |
| `search-server` | `search-server` | 8080 | Search server (REST, WebSocket, Web UI). Built from the `Dockerfile` |

Check that it works:

```bash
curl http://localhost:8080/api/health
# {"status":"ok","ollama":true}
```

While Ollama is still pulling the model, `"ollama"` is `false`. The server keeps working, and `ollama` mode simply adds no synonyms (see [section 4](#post-apisearch)).

Prepare the dataset (skip this if `./data` already contains your files; details in [section 3](#3-dataset)):

```bash
python generate_dataset.py                 # generates 10,000 files into ./data
docker compose restart search-server       # let the server pick up the new data
```

Build the index and run the first search:

```bash
curl -X POST http://localhost:8080/api/index -H "Content-Type: application/json" -d '{"threads": 4}'
curl -X POST http://localhost:8080/api/search -H "Content-Type: application/json" -d '{"query": "space movie", "mode": "keyword"}'
```

Web UI: <http://localhost:8080/>.

---

## 2. Architecture

```
src/
  server/       Main, Server                  HTTP (REST), WebSocket, static files, error handling
  core/         Indexing, Tokenizer           index build, scoring, snippets, tokenization
  collections/  HashTable, Node, WordRecord   custom thread-safe hash table
  pool/         ThreadPoolInterface, WorkerThreadPool   custom thread pool
  llm/          OllamaCli, OllamaCliInterface, OllamaWorker   Ollama integration
  client/       SearchCli                     console client
resources/public/index.html                  Web UI
```

### Inverted index and thread-safe data structure

`collections.HashTable` is a custom implementation that does not use `java.util.HashMap`:

- The table has **100,000 buckets** (`AtomicReferenceArray<Node>`); the bucket index is `(word.hashCode() & 0x7fffffff) % size`.
- Collisions are resolved with chaining (`Node.nextNode`, a `volatile` field).
- Inserting into an empty bucket uses a non-blocking **CAS** (`compareAndSet`). If the CAS fails, the operation is retried.
- Inserting into a non-empty bucket is synchronized on the monitor of the chain head (`synchronized (head)`), so different buckets are updated independently.
- For each word, `WordRecord` keeps a `ConcurrentHashMap<docId, frequency>`, and `merge(docId, freq, Integer::sum)` adds frequencies atomically.
- Lookup (`search`) is lock-free: it walks the chain without taking a monitor.

The index is stored as an immutable snapshot (`IndexState`, a `volatile` field). A new build works on a fresh table, and the finished index is swapped in with a single assignment. Search requests running during a build therefore see a consistent previous state, with no race conditions.

### Thread pool

`pool.WorkerThreadPool` is a custom pool: N worker threads, a task queue (`ArrayDeque`), and synchronization via `lock` + `wait/notify`. `stopPool()` stops accepting tasks, the workers finish the queue, and `waitTermination()` waits for all threads to finish. Each dataset file is a separate pool task. The number of threads is set with the `threads` parameter of `POST /api/index` (1–256).

### Request handling

HTTP requests are handled by the thread pool of the embedded Jetty server (Javalin), so each client request runs in its own thread. Reads from the index are thread-safe (see above).

### Query expansion (Ollama)

`llm.OllamaWorker` sends `POST {OLLAMA_URL}/api/generate` to Ollama (`stream: false`, 90 s timeout) with the prompt from [section 11](#11-prompts-used) and parses the reply into words:

- it drops filler words ("here", "synonyms", "sure", etc.), words that are already in the query, and duplicates;
- it keeps at most **5** synonyms;
- `getWords` is `synchronized`, so Ollama handles one request at a time (a queue with synchronization).

If Ollama is unavailable or returns an error, the synonym list is empty and the search runs on the query words only (no error is returned to the client).

### Tokenization and scoring

- A token is a sequence of ASCII letters `[a-zA-Z]`, lowercased. Digits, punctuation and non-ASCII characters (including Cyrillic) act as separators.
- Files are read as ISO-8859-1.
- `doc_id` is the file name (without the path).
- A document's `score` is the sum over the query terms of `term frequency in the document × weight`. The weight is **1.0** for query words and **0.5** for synonyms from Ollama. The score is not normalized (it can be greater than 1).
- Results are sorted by descending `score`, ties broken by `doc_id` (lexicographically). At most **50** top results are returned.
- `snippet` is a text fragment (about 80 characters before and after) around the first occurrence of any term, with `...` at truncated ends.

### Deployment topology

```
 clients (Web UI, CLI, curl)
          │  HTTP / WebSocket :8080
          ▼
 ┌───────────────────┐   HTTP :11434   ┌──────────────┐
 │  search-server    │ ──────────────► │   ollama     │
 │  (Java, Javalin)  │  /api/generate  │ llama3.2:1b  │
 └───────────────────┘                 └──────────────┘
          │ read-only bind mount
          ▼
     ./data  →  /app/data
```

---

## 3. Dataset

**Path.** The server indexes all regular files in `DATA_DIR` (recursively). In Docker Compose this is `./data` on the host, mounted read-only into the container as `/app/data`.

**Format.**

- a set of English-language `.txt` text files;
- files named `1.txt`, `2.txt`, …, `10000.txt` (one file = one document, and the file name becomes the `doc_id`);
- assignment requirements: at least 10,000 documents and at least 1 GB.

**Generation.** A custom dataset (about 2.5 GB, 10,000 files) is created by the `generate_dataset.py` script. It needs the `wordlist.txt` file (80,000 English words ordered by frequency) next to it.

```bash
python generate_dataset.py                          # -> ./data, 10,000 files
python generate_dataset.py --out data --count 10000 --workers 8
```

Options: `--out`, `--count`, `--min-kb`, `--max-kb`, `--workers`, `--seed`, `--wordlist`, `--vocab`. The text is synthetic: words follow a Zipf distribution and documents have topical word groups, but there is no grammatical meaning.

**Adding your own dataset.** Any other set of English `.txt` files (for example the Stanford dataset from the literature list) can be used instead of the generated one: put the files into `data/` (subdirectories are fine, they are scanned recursively).

**Applying the dataset (required after any change in `./data`).** Whether you generated the dataset or added your own files, restart the search server and rebuild the index:

```bash
docker compose restart search-server
curl -X POST http://localhost:8080/api/index -H "Content-Type: application/json" -d '{"threads": 4}'
```

The index lives in RAM only, so it is empty after every restart and must be rebuilt with `POST /api/index` (or with the build button in the Web UI). Check the result with `GET /api/stats`: the `documents` field should match the number of files in `data/`.

**Memory.** The index is kept in RAM. `docker-compose.yml` sets `JAVA_TOOL_OPTIONS: -Xmx4g` for the container. For a large dataset with diverse vocabulary this may not be enough (`OutOfMemoryError`); increase the value in that case.

---

## 4. REST API

All responses are JSON. Base URL: `http://localhost:8080`.

### Error format

Request and server errors return:

```json
{ "status": "error", "message": "error description" }
```

| HTTP code | When |
|---|---|
| 400 | invalid argument (missing fields, unknown mode, empty query, `threads` out of range) |
| 500 | I/O error (for example, the `DATA_DIR` directory does not exist) |

### GET /api/health

Checks the server state and Ollama availability.

Response `200`:

| Field | Type | Description |
|---|---|---|
| `status` | string | always `"ok"` |
| `ollama` | boolean | `true` if Ollama answers the health request (HTTP 200) |

```json
{ "status": "ok", "ollama": true }
```

### POST /api/index

Builds the index from all files in `DATA_DIR`. The previous index is replaced once the build completes.

Request (the body is optional; without a body or without `threads`, 4 threads are used):

| Field | Type | Required | Description |
|---|---|---|---|
| `threads` | integer | no | number of thread-pool threads, from 1 to 256 (default 4) |

```json
{ "threads": 8 }
```

Response `200`:

| Field | Type | Description |
|---|---|---|
| `status` | string | `"ok"` |
| `documents_indexed` | integer | number of successfully indexed files |
| `time_ms` | integer | index build time, ms |

```json
{ "status": "ok", "documents_indexed": 10000, "time_ms": 18342 }
```

Errors: `400` (`threads` outside 1–256), `500` (data directory unavailable).

### POST /api/search

Search over the inverted index.

Request:

| Field | Type | Required | Description |
|---|---|---|---|
| `query` | string | yes | search query; must contain at least one word made of Latin letters |
| `mode` | string | yes | `"keyword"` uses the query words only; `"ollama"` uses the query words plus synonyms from Ollama |

```json
{ "query": "space movie", "mode": "keyword" }
```

Response `200`:

| Field | Type | Description |
|---|---|---|
| `results` | array | up to 50 results in descending score order |
| `results[].doc_id` | string | document file name |
| `results[].score` | number | score (sum of frequencies with weights, see [section 2](#tokenization-and-scoring)) |
| `results[].snippet` | string | text fragment around the first matched term |
| `time_ms` | integer | search time on the server, ms (including the Ollama request in `ollama` mode) |

```json
{
  "results": [
    { "doc_id": "7488.txt", "score": 100.0, "snippet": "...the movie of space and ..." },
    { "doc_id": "2380.txt", "score": 97.0,  "snippet": "..." }
  ],
  "time_ms": 56
}
```

If there are no matches or the index has not been built, the response is `{"results": [], "time_ms": ...}`.

`400` errors: `Fields 'query' and 'mode' are required`, `Unsupported mode: <mode>`, `Query has no searchable words`.

If Ollama is unavailable, `ollama` mode does not return an error: the search runs as `keyword`.

### GET /api/stats

Statistics of the current index.

Response `200`:

| Field | Type | Description |
|---|---|---|
| `documents` | integer | number of indexed documents |
| `index_build_time_ms` | integer | time of the last build, ms |
| `threads_used` | integer | number of threads used in the last build |

```json
{ "documents": 10000, "index_build_time_ms": 18342, "threads_used": 8 }
```

Before the first index build all fields are `0`.

---

## 5. WebSocket API

Endpoint: `ws://localhost:8080/ws/search`. It streams the stages of the search pipeline **query expansion → keyword search → done**. Each message is a separate JSON text frame with a `stage` field.

For every request the synonyms come from Ollama (as in REST `ollama` mode). There is no mode selection in the WebSocket endpoint.

### Client request

| Field | Type | Required | Description |
|---|---|---|---|
| `query` | string | yes | query text |

```json
{ "query": "space movie" }
```

### Server messages (in order)

**1. `query_expansion`**: query expansion:

| Field | Type | Description |
|---|---|---|
| `data.original` | string | the original query exactly as the client sent it |
| `data.expanded_terms` | array of string | synonyms from Ollama (up to 5), lowercase; an empty array if Ollama is unavailable |

```json
{ "stage": "query_expansion", "data": { "original": "space movie", "expanded_terms": ["cosmos", "film", "galaxy", "cinema"] } }
```

**2. `keyword_search`**: search results:

| Field | Type | Description |
|---|---|---|
| `data.results` | array | same format as in `POST /api/search` (`doc_id`, `score`, `snippet`) |
| `data.time_ms` | integer | index search time, ms (excluding the Ollama request) |

```json
{ "stage": "keyword_search", "data": { "results": [ { "doc_id": "7488.txt", "score": 102.5, "snippet": "..." } ], "time_ms": 12 } }
```

**3. `done`**: completion (no `data` field):

```json
{ "stage": "done" }
```

**`error`**: sent instead of the next stages if the request is invalid (empty query, not JSON, etc.):

| Field | Type | Description |
|---|---|---|
| `data.message` | string | error description |

```json
{ "stage": "error", "data": { "message": "query is required" } }
```

After an `error` message, `done` is not sent. The connection stays open, so further requests can be sent.

---

## 6. CLI client

The console client `client.SearchCli` sends `POST /api/search` and prints the results. It is launched with the `./search` script (Linux/macOS) or `search.bat` (Windows). They look for the built jar in `target/` (run `mvn package` first, see [section 9](#9-building-and-running-without-docker)).

```bash
./search "space movie"                    # keyword mode
./search -m ollama -n 5 "space movie"     # with Ollama expansion, 5 results
./search --json "space movie"             # raw JSON response from the server
```

Without Java installed locally, the client can be run inside the container:

```bash
docker compose exec search-server java -cp app.jar client.SearchCli "space movie"
```

### Options

| Option | Description |
|---|---|
| `-m`, `--mode <keyword\|ollama>` | search mode (default `keyword`) |
| `-n`, `--top <N>` | how many results to print (default 10; the server returns at most 50) |
| `-u`, `--url <url>` | server URL (default: the `SEARCH_URL` variable, otherwise `http://localhost:8080`) |
| `--json` | print the raw JSON response |
| `-h`, `--help` | show help |

All arguments that are not options are joined into the query text.

### Example output

```
Query: "space movie"  (mode: keyword, 56 ms, found: 50, shown: 3)

 1. 7488.txt   score 100.0
    ...the movie of space and the ...
 2. 2380.txt   score 97.0
    ...
 3. 2949.txt   score 97.0
    ...
```

### Exit codes

| Code | Meaning |
|---|---|
| 0 | success |
| 1 | server error, network error or timeout |
| 2 | invalid arguments (no query, unknown mode, `--top` less than 1) |
| 3 | server unreachable |

---

## 7. Web UI

Open <http://localhost:8080/>. The interface has:

- a search form; the query is sent over WebSocket `/ws/search`;
- a visualization of the pipeline stages (query expansion → keyword search → done) with the time of each stage;
- a list of found documents with snippets and the found synonyms;
- indicators for the server, Ollama and the WebSocket connection;
- an index build button (the number of threads is set in the UI) and index statistics;
- a query history.

---

## 8. Configuration

Server environment variables (set in `docker-compose.yml`):

| Variable | Default | Description |
|---|---|---|
| `PORT` | `8080` | server port |
| `DATA_DIR` | `data` (`/app/data` in compose) | dataset directory |
| `OLLAMA_URL` | `http://localhost:11434` (`http://ollama:11434` in compose) | Ollama address |
| `OLLAMA_MODEL` | `llama3.2:1b` | model used for query expansion |
| `JAVA_TOOL_OPTIONS` | `-Xmx4g` in compose | JVM options (memory) |

Client variable: `SEARCH_URL` (server address for the CLI).

---

## 9. Building and running without Docker

Requires JDK 17 and Maven, plus a running Ollama with the model pulled (`ollama pull llama3.2:1b`). Without Ollama the server still works, but `ollama` mode adds no synonyms.

```bash
mvn package                               # target/app.jar
DATA_DIR=./data java -jar target/app.jar  # Windows: set DATA_DIR=data && java -jar target\app.jar
```

---

## 10. Testing

### Search tests

`search_tests.py` compares server responses with a reference computed independently by a direct pass over the dataset (a table with the query, the expected result and the actual result). The result is saved to `results/search_tests.csv`.

```bash
pip install requests
python search_tests.py --data-dir ./data
```

`--data-dir` must be the same directory the server indexes, and the index must be built (`POST /api/index`). For Ollama the test checks that the top score in `ollama` mode is not lower than in `keyword` mode. If Ollama is unavailable, that check is skipped.

### Scalability

Index build time for different thread counts is measured by repeated `POST /api/index` calls with `threads` = 1, 2, 4, 8, … (the time is returned in the `time_ms` field). Search throughput is measured by sending `POST /api/search` from a varying number of concurrent clients. The charts and the analysis of bottlenecks are in the explanatory report.

---

## 11. Prompts used

### Prompt the server sends to Ollama (query expansion)

Template from `llm/OllamaWorker.java`, where `<query>` is the query words joined by spaces:

```
Provide 3-5 single-word synonyms or highly related keywords for the phrase: '<query>'. Return ONLY the words separated by spaces. No explanation. Do not repeat words.
```

Parameters of the `POST /api/generate` request: `model` = `OLLAMA_MODEL`, `prompt` = the text above, `stream` = `false`.