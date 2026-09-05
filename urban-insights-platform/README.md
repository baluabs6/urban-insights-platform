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
  IoT sensors ──▶│   traffic-service    │──▶ PostgreSQL (system of record)
  (traffic/AQI)  │  (Spring Boot :8081) │──▶ Redis (latest-reading cache, anomaly counters)
                 └─────────┬────────────┘
                           │ REST
                           ▼
                 ┌─────────────────────┐
  Citizen app ──▶│  complaint-service   │──▶ MongoDB (flexible complaint documents, geo-index)
                 │  (Spring Boot :8082) │
                 └─────────┬────────────┘
                           │ REST (pulled at query time)
                           ▼
                 ┌─────────────────────────────┐
  Ops team ─────▶│   ai-insight-service         │
  "Why is AQI    │   (Spring Boot :8083)        │
   high in       │   LangChain4j RAG pipeline:  │
   Whitefield?"  │   retrieve → embed → search  │
                 │   → augment prompt → LLM     │
                 └─────────────────────────────┘
```

## Modules

- **traffic-service** — ingests sensor readings, persists to PostgreSQL, caches "latest
  reading" and "zone summary" in Redis, and flags anomalies with a z-score check
  against a 24h rolling mean/stddev.
- **complaint-service** — accepts citizen complaints as MongoDB documents (geo-indexed,
  tag/urgency fields left open for a GenAI classifier to populate), supports
  zone/category/near-me queries.
- **ai-insight-service** — the GenAI/RAG layer. On each question it re-indexes the
  relevant zone's live traffic summary + complaints into an in-memory vector store
  (swap for pgvector/Milvus/Pinecone in production), retrieves the top-k relevant
  segments, and asks an LLM to answer **using only that retrieved context**.

## Running locally

```bash
docker compose up --build
```

This starts PostgreSQL, MongoDB, Redis, and all three services.

## Example calls

```bash
# 1. Push a sensor reading
curl -X POST http://localhost:8081/api/traffic/ingest \
  -H "Content-Type: application/json" \
  -d '{"sensorId":"AQI-WF-01","sensorType":"AQI","zone":"Whitefield","latitude":12.97,"longitude":77.75,"value":310,"unit":"AQI"}'

# 2. File a citizen complaint
curl -X POST http://localhost:8082/api/complaints \
  -H "Content-Type: application/json" \
  -d '{"citizenId":"C123","category":"GARBAGE","description":"Garbage overflow near main road, causing bad smell","zone":"Whitefield","latitude":12.97,"longitude":77.75}'

# 3. Ask the AI/RAG layer
curl -X POST http://localhost:8083/api/insights/ask \
  -H "Content-Type: application/json" \
  -d '{"zone":"Whitefield","question":"Why might air quality be poor here today, and are there related complaints?"}'
```

## AI-powered features (added)

All of these live in `ai-insight-service`, with `complaint-service` calling the first
two synchronously at submission time (fail-soft: falls back to a local heuristic if
the AI service is unreachable, so submissions never fail because of an AI outage).

| Feature | Endpoint | What it does |
|---|---|---|
| GenAI complaint classification | `POST /api/ai/classify-complaint` | LLM reads the free-text description and returns category, department, urgency score, tags as structured JSON — replaces keyword-matching |
| Duplicate complaint detection | `POST /api/ai/duplicate-check` | Embeds the new complaint and compares (cosine similarity) against existing open complaints in the same zone to catch "same pothole, 50 reports" |
| Multi-zone comparative Q&A | `POST /api/ai/compare-zones` | Ask a question spanning several zones at once, e.g. "Which of these has the worst AQI and why?" |
| Anomaly root-cause explanation | `GET /api/ai/anomaly-explanation/{zone}` | Turns a raw z-score anomaly flag into a plain-language explanation, cross-referencing nearby citizen complaints for plausible causes |
| Automated daily city briefing | `GET /api/ai/city-briefing?refresh=true` | LLM-written summary (status / zones needing attention / recommended actions) across all configured zones; also runs on a schedule (`city.briefing-cron`, default 7 AM daily) |
| Single-zone grounded Q&A (existing) | `POST /api/insights/ask` | The original RAG endpoint — retrieves live zone data, embeds it, and grounds the LLM's answer in it |

Example — classify + duplicate-check are called automatically when you submit a complaint:

```bash
curl -X POST http://localhost:8082/api/complaints \
  -H "Content-Type: application/json" \
  -d '{"citizenId":"C123","category":"OTHER","description":"Huge pothole near the main gate, cars are swerving dangerously","zone":"Whitefield","latitude":12.97,"longitude":77.75}'
```

The response now includes `category`, `assignedDepartment`, `urgencyScore`, `tags`,
`classificationSource` ("AI" or "HEURISTIC_FALLBACK"), and `likelyDuplicate` — all
filled in by the AI layer.

Other examples:

```bash
# Compare zones
curl -X POST http://localhost:8083/api/ai/compare-zones \
  -H "Content-Type: application/json" \
  -d '{"zones":["Whitefield","Koramangala"],"question":"Which zone needs urgent attention today and why?"}'

# Explain an anomaly
curl http://localhost:8083/api/ai/anomaly-explanation/Whitefield

# Get (or force-refresh) the daily briefing
curl "http://localhost:8083/api/ai/city-briefing?refresh=true"
```

Configure the zones covered by the briefing/comparison features in
`ai-insight-service/src/main/resources/application.yml` under `city.zones`.

## Platform hardening & new features (round 2)

| Feature | Where | What it does |
|---|---|---|
| Persistent vector store | `ai-insight-service` (`LangChainConfig`) | Embeddings now live in **pgvector** (reusing the Postgres instance, table `urban_embeddings`) instead of an in-memory store, so RAG context survives restarts. Falls back to in-memory automatically if Postgres/pgvector isn't reachable. Requires the `pgvector` extension — the provided `docker-compose.yml` uses the `pgvector/pgvector:pg16` image and an init script (`infra/postgres-init/001-pgvector.sql`) that runs `CREATE EXTENSION IF NOT EXISTS vector;` automatically. |
| Circuit breakers | Both `UrbanDataClient` (ai-insight-service) and `AiInsightClient` (complaint-service) | Resilience4j `@CircuitBreaker` wraps every inter-service HTTP call. After repeated failures the breaker opens and fails fast to a safe fallback (empty data / heuristic) instead of piling up blocked calls on a dead dependency. Config + thresholds in each service's `application.yml` under `resilience4j.circuitbreaker.instances`; state exposed at `/actuator/circuitbreakers`. |
| Multi-language complaint support | `ai-insight-service` (`LanguageSupportService`) | Detects the language of a complaint description (or a chatbot question) and translates it to English before classification/RAG, so citizens can write in Hindi or any regional language. Answers can be translated back into the citizen's language too. |
| Complaint-status chatbot | `ai-insight-service` (`ComplaintStatusChatService`, `POST /api/ai/complaint-status`) | Citizen-facing "where's my complaint?" endpoint — fetches one complaint by ID from complaint-service and grounds the LLM's answer strictly in that record. Multi-language aware. This is the foundation for wiring in a WhatsApp/SMS front end later. |
| SLA tracking & auto-escalation | `ai-insight-service` (`SlaEscalationService`, `GET /api/ai/sla-escalations`) | Per-category SLA thresholds (e.g. garbage: 24h, pothole: 120h); a daily scheduled sweep (and on-demand endpoint) finds breaching open/in-progress complaints and drafts an escalation note via the LLM for each one. |

New/changed endpoints:

```bash
# Complaint status chatbot (multi-language capable)
curl -X POST http://localhost:8083/api/ai/complaint-status \
  -H "Content-Type: application/json" \
  -d '{"complaintId":"<id from a submitted complaint>","question":"Has anyone looked at this yet?"}'

# SLA breaches + draft escalation emails
curl http://localhost:8083/api/ai/sla-escalations

# Look up a single complaint (needed by the chatbot; also useful standalone)
curl http://localhost:8082/api/complaints/<id>

# Complaints filtered by status
curl http://localhost:8082/api/complaints/status/OPEN
```

`classify-complaint` responses now also include `detectedLanguage`, and complaint
descriptions written in Hindi/Tamil/etc. are classified correctly without any change
to how citizens submit complaints.

Deferred for a future round (need infra not yet in this repo): Kafka streaming
ingestion, WebSocket push to dashboards, an actual WhatsApp/SMS channel in front of
the status chatbot, an API gateway + service discovery, and OAuth2/JWT auth.

## Using a different LLM

`ai-insight-service` talks to any OpenAI-compatible chat/embeddings endpoint. To use a
free local model instead of a paid API, run [Ollama](https://ollama.com) and set:

```bash
export OPENAI_BASE_URL=http://localhost:11434/v1
export OPENAI_MODEL=llama3
```

No code changes needed — LangChain4j's `OpenAiChatModel` builder just points elsewhere.

## Extending this to other urban problems

The same pattern (Postgres for structured sensor data + Mongo for unstructured
citizen/field data + Redis for hot paths + RAG for natural-language Q&A) generalizes to:
- Water supply monitoring & leakage complaints
- Public transport delay prediction + commuter complaints
- Streetlight/power outage tracking
- Waste collection route optimization

You'd typically add a **Kafka** topic between ingestion and storage for true streaming
scale (this reference keeps direct REST ingestion for simplicity), and a **scheduler**
(Spring `@Scheduled` or a separate job service) to periodically re-index the vector
store instead of doing it synchronously per-query.

## Notes / production hardening not included here (kept out for clarity)

- No API gateway / service discovery (Eureka/Consul) — services call each other by
  fixed URL for simplicity.
- No authentication/authorization (add Spring Security + OAuth2/JWT).
- In-memory vector store — swap for `PgVectorEmbeddingStore` to persist embeddings.
- No circuit breaker (add Resilience4j around the `UrbanDataClient` calls).
