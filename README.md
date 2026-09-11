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

## How This Differs from a Real-Time Production Application

The codebase uses real-time-*style* building blocks — Kafka topics, async
consumers, Redis caching, circuit breakers — and several rounds of hardening
went into narrowing the gap between "demo" and "production." But there is
still an important difference between an architecture that *uses* real-time
patterns and a system that meets the operational guarantees of genuine
real-time production software. Concretely:

| Dimension | This platform | A true real-time production system |
|---|---|---|
| **Kafka topology** | Single broker, `acks=1`, replication factor 1 — a broker crash can lose in-flight messages | A cluster of 3+ brokers with a replication factor ≥3, so no single node failure loses data |
| **Delivery guarantees** | "Save, then publish" is two separate, non-atomic steps in `traffic-service`/`complaint-service`; a crash between them silently drops the event (partly mitigated, not fixed, by periodic reclassification sweeps) | A transactional outbox (or Kafka transactions) makes the DB write and the publish atomic, so nothing is silently lost |
| **Message contract** | Kafka payloads are untyped `Map<String,Object>` with no schema | A schema registry (Avro/Protobuf) enforces and versions the contract between producers and consumers |
| **Failure handling** | No dead-letter topic — a message that fails processing repeatedly is retried in place or dropped | Dead-letter queues isolate poison messages so they don't block or repeatedly fail the whole consumer group |
| **Latency guarantees** | "Fast" in practice (sub-second for most paths) but with no enforced SLA, no backpressure signaling, and no load-shedding under overload | Explicit latency SLOs, backpressure, and load-shedding are designed in and continuously measured |
| **Scaling model** | Manual `docker compose` topology; a single Resilience4j rate limiter was originally per-JVM (later fixed with a Redis-backed distributed limiter) but there is still no autoscaling | Horizontal Pod Autoscaling driven by real signals (e.g. Kafka consumer lag), not just CPU |
| **Service discovery** | Hardcoded service URLs (`http://traffic-service:8081`, etc.) baked into config | A service registry / API gateway (Eureka, Consul, Kubernetes DNS) so services are discovered, not hardcoded |
| **Data lifecycle** | No partitioning or retention policy on the fast-growing `sensor_readings` table; no Mongo sharding; no Redis clustering | Time-based partitioning/retention on hot tables, sharded document stores, and clustered caches sized for real traffic volumes |
| **Security** | A single shared-secret API key (two tiers: public/admin) enforced per service; secrets have working (if flagged) defaults that must be manually overridden | OAuth2/JWT with per-role scopes, secrets pulled from a vault/secrets manager with no functional defaults, and mutual TLS between internal services |
| **Observability** | Prometheus scraping + basic Micrometer metrics (LLM call latency/cost, circuit-breaker state); no pre-built dashboards or alerting rules | Full observability stack with dashboards, alerting thresholds, distributed tracing, and on-call runbooks |
| **Testing & validation** | A handful of fast unit tests around the riskiest logic (heuristics, prompt-injection detection, anomaly scoring); no integration or load testing; `mvn compile` has not been run against Maven Central from within this environment | Full unit/integration/contract/load test suites running in CI against every change, plus chaos/failure-injection testing |
| **Concurrency model** | Some inter-service calls still block the servlet thread (`.block()` on a reactive client) rather than being fully non-blocking end-to-end | Fully asynchronous, non-blocking I/O throughout the request path, sized to the actual concurrency the system must sustain |

In short: this project demonstrates the *shape* of a real-time, event-driven,
AI-augmented system — asynchronous ingestion, event-driven indexing, caching,
circuit breakers, rate limiting, and a working RAG pipeline — well enough to
be a solid learning reference or a starting point for a proof of concept. It
does not carry the durability guarantees, schema discipline, elastic scaling,
security hardening, or operational tooling (alerting, tracing, on-call
runbooks) that a system handling real citizen data and real city
infrastructure in production would need before going live.
