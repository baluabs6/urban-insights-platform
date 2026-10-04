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

### 1. System Architecture

```mermaid
flowchart TB
    IOT["IoT Sensors<br/>traffic / AQI"]
    CITIZEN["Citizen App<br/>complaints, photos, voice"]
    OPS["Ops Team<br/>'Why is AQI high in Whitefield?'"]

    TRAFFIC["<b>traffic-service</b> :8081<br/>ingest · z-score anomalies · forecast"]
    COMPLAINT["<b>complaint-service</b> :8082<br/>intake · heuristic classify · status / override"]
    AI["<b>ai-insight-service</b> :8083<br/>RAG · GenAI features · consumers · schedulers"]

    KAFKA{{"<b>Apache Kafka</b> (KRaft)<br/>traffic.readings · traffic.anomalies<br/>complaint.created · complaint.classification.overridden"}}

    PG[("PostgreSQL<br/>sensor time-series")]
    REDIS[("Redis<br/>hot cache · semantic cache")]
    MONGO[("MongoDB<br/>complaint documents")]
    PGV[("pgvector<br/>RAG embeddings")]

    LLM["LLM + Embeddings + Whisper<br/>OpenAI-compatible / Ollama"]
    OBS["Prometheus :9090 → Grafana :3000"]

    IOT -->|"POST /ingest"| TRAFFIC
    CITIZEN -->|"POST /complaints"| COMPLAINT
    OPS -->|"ask / GenAI APIs"| AI

    TRAFFIC <-->|"readings · anomalies"| KAFKA
    COMPLAINT -->|"created · overridden"| KAFKA
    KAFKA -->|"anomalies · created · overridden"| AI

    AI -->|"REST pull"| TRAFFIC
    AI -->|"REST pull + PATCH callback"| COMPLAINT
    COMPLAINT -.->|"REST classify"| AI

    TRAFFIC --> PG
    TRAFFIC <--> REDIS
    COMPLAINT --> MONGO
    AI <--> PGV
    AI <--> REDIS
    AI --> LLM

    TRAFFIC -.-> OBS
    COMPLAINT -.-> OBS
    AI -.-> OBS

    classDef svc fill:#e8f1ff,stroke:#2f6fdb,stroke-width:2px,color:#0b2a5b;
    classDef bus fill:#fff4e0,stroke:#e08a00,stroke-width:2px,color:#5a3600;
    classDef store fill:#e9f7ec,stroke:#2e9e4f,color:#0d3b1b;
    classDef ext fill:#f3e9ff,stroke:#7b3fe4,color:#2d0f5e;
    classDef obs fill:#f2f2f2,stroke:#777,color:#222;
    classDef client fill:#fff,stroke:#555,color:#222;
    class TRAFFIC,COMPLAINT,AI svc;
    class KAFKA bus;
    class PG,MONGO,REDIS,PGV store;
    class LLM ext;
    class OBS obs;
    class IOT,CITIZEN,OPS client;
```

### 2. RAG Pipeline (`ai-insight-service`)

```mermaid
flowchart LR
    Q["Ops question<br/>+ zone"] --> SC{"Semantic cache<br/>hit? (Redis)"}
    SC -->|"hit"| R["Response"]
    SC -->|"miss"| RW["Query rewrite<br/>→ sub-queries"]
    RW --> RET["Hybrid retrieval (parallel)<br/>vector (pgvector) + keyword + recency"]
    RET --> MERGE["Merge · de-dup<br/>rerank → top-K (5 / 8)"]
    MERGE --> AUG["Augment prompt<br/>(untrusted-data wrapping)"]
    AUG --> LLM["LLM generate<br/>(LangChain4j)"]
    LLM --> FC["Faithfulness check"]
    FC -->|"score ≥ 0.6"| STORE["Store in<br/>semantic cache"]
    FC --> R
    STORE --> R

    classDef step fill:#e8f1ff,stroke:#2f6fdb,color:#0b2a5b;
    classDef decision fill:#fff4e0,stroke:#e08a00,color:#5a3600;
    class RW,RET,MERGE,AUG,LLM,FC,STORE step;
    class SC decision;
```

### 3. Complaint Lifecycle (event-driven enrichment)

```mermaid
sequenceDiagram
    autonumber
    actor C as Citizen
    participant CS as complaint-service
    participant M as MongoDB
    participant K as Kafka
    participant AI as ai-insight-service
    participant V as pgvector
    participant L as LLM

    C->>CS: POST /api/complaints
    CS->>CS: Instant heuristic classification
    CS->>M: Save complaint document
    CS-->>C: 201 Created (never blocked by AI)
    CS->>K: publish complaint.created
    K->>AI: consume complaint.created
    AI->>V: Embed and index complaint (index-on-write)
    AI->>L: Classify · detect duplicates · verify photos · score urgency
    L-->>AI: category, department, urgency, tags
    AI->>CS: PATCH /api/complaints/{id}/classification
    CS->>M: Update enriched fields
    Note over AI,CS: Scheduled jobs — reclassification sweep (15 min), hotspot prediction (hourly),<br/>SLA escalation (08:00), city briefing (07:00)
    CS->>K: publish complaint.classification.overridden (manual override)
    K->>AI: consume override → feeds classification feedback loop
```

### 4. Component Summary

| Component | Port | Role | Stores / Dependencies |
|---|---|---|---|
| `traffic-service` | 8081 | Sensor ingestion, rolling z-score anomaly detection, zone summaries, forecasting | PostgreSQL, Redis, Kafka |
| `complaint-service` | 8082 | Complaint intake, instant heuristic classification, status/override workflow, geo queries | MongoDB, Kafka |
| `ai-insight-service` | 8083 | RAG Q&A, GenAI features (classification, duplicates, zone comparison, anomaly explanation, SLA escalation, status chatbot, city briefing, hotspots, photo and voice handling) | pgvector, Redis, Kafka, LLM provider |
| Kafka | 9092 | Event backbone (`traffic.readings`, `traffic.anomalies`, `complaint.created`, `complaint.classification.overridden`) | — |
| Prometheus / Grafana | 9090 / 3000 | Metrics scraping and the LLM cost and latency dashboard | All three services |

## About This Application and Different from other applications

### About This Application

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

### Different from Other Applications

Most sample or tutorial applications pick a single technology and show it in
isolation — a CRUD app over one database, a standalone chatbot, or a Kafka
"hello world." Urban Insights Platform is different in a few specific ways:

- **Polyglot persistence used for a reason, not for show.** Each data store
  was chosen because of the shape of the data it holds — PostgreSQL for
  structured, aggregate-heavy time-series sensor readings; MongoDB for
  irregularly-shaped citizen complaints; Redis for sub-millisecond hot reads;
  and pgvector for persistent semantic search — rather than routing
  everything through one general-purpose database.
- **The AI layer is grounded, not a bolt-on chatbot.** Instead of a generic
  wrapper around an LLM API, `ai-insight-service` implements a full RAG
  pipeline (query rewriting → hybrid retrieval → reranking → grounded
  generation → a faithfulness check) so answers are tied to live sensor and
  complaint data instead of the model's own unverified output.
- **Event-driven by default, not request/response everywhere.** Sensor
  ingestion and complaint submission both return immediately and do the real
  work (persistence, classification, indexing) asynchronously off Kafka,
  rather than making the caller wait on every downstream step — including the
  slowest ones (LLM/embedding calls).
- **Multiple cooperating services, not one monolith.** `traffic-service`,
  `complaint-service`, and `ai-insight-service` are independently deployable
  Spring Boot applications that call each other over authenticated REST and
  Kafka, exercising patterns (circuit breakers, retries, rate limiting,
  service-to-service auth) that a single-service sample app has no reason to
  demonstrate.
- **Honest about its own gaps.** The codebase and its documentation explicitly
  call out what was deferred (schema-less Kafka payloads, no dead-letter
  topic, single-broker Kafka, hardcoded service URLs, etc.) rather than
  presenting itself as deployment-ready — which is unusual for a reference
  project, but intentional here: it's meant to teach the pattern honestly, not
  to look finished.

