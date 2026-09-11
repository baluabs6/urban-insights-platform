# Urban Insights Platform

A data engineering reference architecture for **urban civic problems** (traffic congestion,
air quality, citizen complaints) built as **Java Spring Boot microservices**, combining:

| Concern | Technology | Why |
|---|---|---|
| Structured, high-volume time-series data | **PostgreSQL** | Sensor readings, indexed by sensor/zone/time, needs SQL aggregates (AVG/STDDEV) for anomaly detection |
| Hot, low-latency reads | **Redis** | Dashboard "latest reading per sensor" served in sub-ms; short-TTL cache-aside + cache-put pattern |
| Flexible, unstructured/semi-structured data | **MongoDB** | Citizen complaints vary wildly in shape (photos, free text, geo, tags) — document model over rigid tables |
| Statistical/ML scoring | **AI module** | Lightweight z-score anomaly detector in `traffic-service` (stand-in for a trained model) |
| Natural-language reasoning | **GenAI / LLM** | LangChain4j `ChatLanguageModel` (OpenAI-compatible; swappable for a local model via Ollama/vLLM) |
| Grounded answers over live data | **RAG** | `ai-insight-service` retrieves fresh data from the other two services, embeds it, and grounds the LLM's answer in it |
| Orchestration of retrieve→augment→generate | **LangChain4j** (LangChain for the JVM) | `RagInsightService` implements the RAG chain |

## Architecture

```
                 ┌─────────────────────┐
  IoT sensors ──▶│   traffic-service    │──▶ Kafka "traffic.readings" ──▶ consumer ──▶ PostgreSQL
  (traffic/AQI)  │  (Spring Boot :8081) │                                          └─▶ Redis (cache)
                 └─────────┬────────────┘
                           │ Kafka "traffic.anomalies"
                           ▼
                 ┌─────────────────────┐
  Citizen app ──▶│  complaint-service   │──▶ MongoDB (flexible complaint documents, geo-index)
                 │  (Spring Boot :8082) │──▶ Kafka "complaint.created"
                 └─────────┬────────────┘        │
                           │ REST (PATCH callback,│ consumed by
                           │ paginated pulls)     ▼
                 ┌─────────────────────────────────┐
  Ops team ─────▶│   ai-insight-service             │──▶ pgvector (persistent RAG index)
  "Why is AQI    │   (Spring Boot :8083)            │──▶ Redis (semantic cache, SLA dedup, briefing)
   high in       │   Kafka consumers index-on-write; │
   Whitefield?"  │   LangChain4j RAG pipeline:       │
                 │   rewrite → retrieve → rerank      │
                 │   → augment prompt → LLM → verify  │
                 └─────────────────────────────────┘
```

## About This Application

Urban Insights Platform is a **reference / demo architecture**, not a deployed
production system. It exists to show, in working code, how a city-scale civic
platform can combine three very different data shapes (time-series sensor
readings, freeform citizen complaints, and vector embeddings for
retrieval-augmented generation) behind three cooperating Spring Boot services.

**What it actually does:**

- **`traffic-service`** ingests IoT-style traffic and air-quality-index (AQI)
  sensor readings, persists them to PostgreSQL, keeps a "latest reading per
  sensor" and per-zone summary hot in Redis, and flags statistical anomalies
  (a rolling z-score over each sensor's recent readings) without hitting the
  database on every single reading.
- **`complaint-service`** accepts citizen complaints (garbage, potholes, water
  leakage, etc.) as flexible MongoDB documents — photos, free text, geo-tags,
  and category all vary complaint to complaint, which is why this data lives
  in a document store rather than a rigid relational table. It classifies a
  complaint instantly with a cheap local heuristic so submission never blocks
  or fails because of an AI outage, then republishes the event for
  asynchronous enrichment.
- **`ai-insight-service`** is the GenAI/RAG brain of the platform. It listens
  for new complaints and anomalies, embeds them into a persistent `pgvector`
  index the moment they're written, and answers natural-language questions
  ("Why is AQI high in Whitefield, and are there related complaints?") by
  retrieving the freshest relevant data, grounding an LLM's answer in it, and
  running a faithfulness check before returning that answer. It also drives
  the higher-level GenAI features layered on top: automatic complaint
  classification, duplicate-complaint detection, multi-zone comparison,
  anomaly explanation, SLA breach detection with drafted escalation notes,
  a citizen-facing "where's my complaint?" chatbot, and a scheduled daily
  city briefing.
- All inter-service calls go through a shared-secret `X-API-Key` (two-tier:
  public vs. admin), wrapped in Resilience4j retries and circuit breakers so
  one service being slow or down degrades gracefully instead of cascading.
- The whole stack — PostgreSQL (with pgvector), MongoDB, Redis, Kafka,
  Prometheus, and Grafana — runs locally with a single `docker compose up`,
  and `seed-data/` can populate it with realistic dummy sensor readings and
  complaints for a demo.

**Who this is for:** engineers who want a concrete, runnable example of
combining polyglot persistence (SQL + document + cache + vector), an
event-driven backbone (Kafka), and a grounded LLM/RAG layer in one coherent
Java codebase — not a finished product ready to serve a real city.

