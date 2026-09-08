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

All inter-service HTTP calls require an `X-API-Key` header (shared secret,
`ApiKeyAuthFilter` in each service) and are wrapped in Resilience4j `@Retry` +
`@CircuitBreaker`.

## Modules

- **traffic-service** — publishes sensor readings to Kafka (`traffic.readings`) and
  returns immediately; a consumer persists to PostgreSQL, caches "latest reading" /
  "zone summary" in Redis (zone summary via SQL aggregates, not loading every row),
  and flags anomalies with a z-score check against a Redis-maintained rolling window
  per sensor (last 100 readings — see "Scalability fixes" below; this used to be two
  Postgres aggregate queries per reading, now zero), publishing anomalies to `traffic.anomalies`.
- **complaint-service** — accepts citizen complaints as MongoDB documents (geo-indexed),
  saves instantly with a fast heuristic classification, and publishes `complaint.created`
  for async enrichment. Exposes paginated zone/status queries and a callback endpoint
  ai-insight-service uses to write back the real AI classification.
- **ai-insight-service** — the GenAI/RAG layer. Kafka listeners index new complaints/
  anomalies into a **persistent pgvector store** the moment they're written (not
  re-embedded per query). RAG queries go through query rewriting, hybrid
  (vector + keyword + recency) retrieval, cited/grounded generation, and a
  faithfulness check, with a Redis-backed semantic cache short-circuiting repeat
  questions. Also runs the async complaint classifier, a reclassification sweep,
  SLA escalation drafting, and the daily city briefing.

## Running locally

```bash
docker compose up --build
```

This starts PostgreSQL (with pgvector), MongoDB, Redis, Kafka, Prometheus,
Grafana, and all three services. **Every endpoint below requires an
`X-API-Key: change-me-in-prod` header** (or whatever you set
`INTERNAL_API_KEY`/`INTERNAL_ADMIN_API_KEY` to before running compose) — some
endpoints require the *admin* tier specifically (see "Security & feature gap
fixes" below); curl examples elsewhere in this README that omit the header
for brevity will 401/403 against a real running instance; add it as shown in
"Example calls" below.

- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000` (default `admin`/`admin` — see `GRAFANA_ADMIN_PASSWORD`)

To populate it with realistic dummy data instead of starting from an empty
city, see [`seed-data/README.md`](seed-data/README.md) — `cd seed-data &&
./load_seed_data.sh` after compose is up.

## Example calls

```bash
KEY="change-me-in-prod"

# 1. Push a sensor reading — publishes to Kafka and returns immediately (202).
#    Actual processing happens asynchronously in SensorReadingConsumer.
curl -X POST http://localhost:8081/api/traffic/ingest \
  -H "Content-Type: application/json" -H "X-API-Key: $KEY" \
  -d '{"sensorId":"AQI-WF-01","sensorType":"AQI","zone":"Whitefield","latitude":12.97,"longitude":77.75,"value":310,"unit":"AQI"}'
# -> 202 {"status":"accepted","sensorId":"AQI-WF-01"}

# Synchronous alternative for local testing (returns the saved row directly):
curl -X POST http://localhost:8081/api/traffic/ingest-sync \
  -H "Content-Type: application/json" -H "X-API-Key: $KEY" \
  -d '{"sensorId":"AQI-WF-01","sensorType":"AQI","zone":"Whitefield","latitude":12.97,"longitude":77.75,"value":310,"unit":"AQI"}'

# 2. File a citizen complaint — saves instantly with a heuristic classification,
#    then ai-insight-service classifies it for real asynchronously.
curl -X POST http://localhost:8082/api/complaints \
  -H "Content-Type: application/json" -H "X-API-Key: $KEY" \
  -d '{"citizenId":"C123","category":"GARBAGE","description":"Garbage overflow near main road, causing bad smell","zone":"Whitefield","latitude":12.97,"longitude":77.75}'

# 3. Ask the AI/RAG layer
curl -X POST http://localhost:8083/api/insights/ask \
  -H "Content-Type: application/json" -H "X-API-Key: $KEY" \
  -d '{"zone":"Whitefield","question":"Why might air quality be poor here today, and are there related complaints?"}'
```

## Real-time / production-readiness fixes (round 3)

A prior review of this codebase surfaced concrete gaps between "demo" and
"real-time, production" behavior. Here's what changed and why:

| Gap | Fix |
|---|---|
| Ingestion was one blocking REST call per sensor reading | `traffic-service` now publishes to Kafka (`traffic.readings`) and returns `202 Accepted` immediately; a separate `SensorReadingConsumer` does the DB write + anomaly scoring on Kafka's own threads, not the servlet thread. `/api/traffic/ingest-sync` is kept as a synchronous fallback for local testing. |
| Complaint submission blocked on 2+ LLM/embedding round-trips | `complaint-service` now saves instantly with a fast local heuristic and publishes `complaint.created`; `ai-insight-service`'s `ComplaintCreatedListener` does the real classification + duplicate-check asynchronously and PATCHes the result back. |
| RAG re-embedded a whole zone's data on every question | Indexing is now event-driven: `ComplaintCreatedListener`/`TrafficAnomalyListener` index-on-write with a deterministic ID + best-effort upsert (`UrbanDataRetriever.indexComplaintDocument/indexAnomalyDocument`). The old pull-and-reindex-per-query path still exists behind `rag.index-on-query-fallback=true` for cold starts / Kafka outages. |
| Heuristic-classified complaints stayed mis-tagged forever | `ReclassificationSweepService` runs every 15 minutes, retrying anything still `PENDING_AI`/`HEURISTIC_FALLBACK` (exposed at `GET /api/complaints/needing-reclassification`). |
| SLA sweep re-drafted the same escalation every run | `SlaEscalationService` now checks a Redis key (`sla-escalated:{id}`, 24h TTL) before drafting again. |
| City briefing lived in a single in-memory field | Persisted in Redis (`city-briefing:latest`) — survives restarts and stays consistent across multiple instances. |
| Unbounded list endpoints (memory + LLM context blowups) | `complaint-service` and `traffic-service` list endpoints now return paginated `Page<T>`; `UrbanDataClient` unwraps `content` and caps page sizes before anything reaches an LLM prompt. `traffic-service`'s zone summary now uses SQL aggregates (`COUNT`/`AVG`) instead of loading every row. |
| No auth — anyone could read citizen PII or call LLM endpoints for free | A shared-secret `X-API-Key` header, enforced by an `ApiKeyAuthFilter` in all three services; inter-service `WebClient`s send it automatically. This is intentionally simple — swap for OAuth2/JWT with per-role scopes before any real deployment. |
| Citizen-supplied text went straight into LLM prompts unguarded | `PromptSafetyUtils` wraps untrusted text in explicit delimiters and logs suspicious patterns (jailbreak phrasing, fake role tags); wired into complaint classification and the status chatbot. |
| Circuit breakers alone don't help transient blips | `UrbanDataClient` now stacks `@Retry` (exponential backoff, 3 attempts) inside `@CircuitBreaker` on every inter-service call. |
| `docker-compose` build was broken (`./mvnw` doesn't exist in this repo) | All three Dockerfiles now use a real Maven image and build from the **repo root** context (required because each module's `pom.xml` has a `<parent>` pointing at the root POM) via `mvn -pl <module> -am package`. |

Still explicitly deferred (flagged, not implemented, given scope): removing `.block()` calls from servlet threads for full non-blocking I/O, distributed tracing/LLM cost metrics, and OAuth2/JWT with per-role scopes.

## Real-time / production-readiness fixes (round 4)

Another review pass found gaps in what round 3 shipped, plus pre-existing ones it hadn't reached:

| Gap | Fix |
|---|---|
| SLA/reclassification sweeps silently capped at one page (50 items) — a real backlog beyond that was never checked, with no error | `UrbanDataClient.fetchAllPages()` now walks every page (up to a 50-page/5,000-item safety cap, which itself logs a warning if hit instead of failing silently). `complaint-service`'s `/needing-reclassification` endpoint is now paginated to match. |
| Duplicate detection re-fetched and re-embedded every open complaint in a zone on every single check (N embedding-API calls per submission) | `DuplicateDetectionService` now queries the persistent pgvector index directly (already populated by index-on-write) via a new `UrbanDataRetriever.retrieve(question, zone, type, excludeId, topK)` overload — one embedding call (the query itself) instead of one per existing complaint. `DuplicateCheckRequest` gained an `excludeComplaintId` field so a complaint doesn't match its own just-indexed copy. |
| RAG sub-query retrieval (query rewriting expands one question into 2-4) ran fully sequentially — pure added latency for independent operations | `RagInsightService` now fires all sub-query retrievals concurrently via `CompletableFuture` on a dedicated bounded thread pool, instead of a `for` loop awaiting each one in turn. |
| `kafkaTemplate.send()`'s result was discarded entirely in both `traffic-service` and `complaint-service` — a failed publish was invisible; the client got a success response for data that was silently dropped | Both now attach `.whenComplete()` logging to the async result. `traffic-service` additionally falls back to a synchronous direct-ingest path if the publish call itself throws, so a reading is written even in that case (not a substitute for a transactional outbox — see below). |
| `docker-compose` build was still theoretically untested end-to-end | Re-validated: all `application.yml`/`docker-compose.yml` parse as valid YAML, all `pom.xml` as valid XML, all Java files brace-balanced, and every cross-service method signature checked against its caller. Full `mvn compile` still isn't possible in the environment these fixes were authored in (no Maven Central access) — run it locally before deploying. |

**Still open, honestly**: no transactional outbox (the save-then-publish in both
services is still two non-atomic steps — a crash between them loses the event,
mitigated but not fixed by the reclassification sweep and Kafka delivery
logging above), single-node Kafka with `acks=1` and replication factor 1 (no
durability guarantee under a broker failure), no dead-letter topic for
messages that fail processing repeatedly, and Kafka payloads are untyped
`Map<String,Object>` with no schema contract between services.

## Security & feature gap fixes (round 5)

| Gap | Fix |
|---|---|
| Prompt injection only partially guarded — complaint descriptions flowed unwrapped into RAG context, SLA drafts, query rewriting, faithfulness checks, and zone comparisons | `PromptSafetyUtils.wrapUntrusted()` now wraps every remaining site: `RagInsightService`, `SlaEscalationService`, `QueryRewriteService`, `FaithfulnessChecker`, `ZoneComparisonService`, `AnomalyExplanationService`. Closes a *stored* injection vector — a malicious complaint filed once could otherwise influence every future RAG answer about that zone. |
| No input size caps | `@Size` validation on every free-text field (`description` ≤2000 chars, `question` ≤1000, etc.) and a 1-20 zone cap on `CompareZonesRequest` — previously unbounded, meaning a multi-megabyte string was legal and would blow up embedding/LLM cost or context limits. |
| No rate limiting on LLM-backed endpoints | Distributed, Redis-backed rate limiter (see "Scalability" below) on `/api/insights/ask` and all `/api/ai/*` endpoints. |
| Only one shared API key for everything, including reading any citizen's PII | Two-tier keys (`X-API-Key` PUBLIC vs ADMIN) — `ApiKeyAuthFilter` + `AdminOnlyInterceptor` + `WebMvcConfig` in all three services gate PII-exposing/privileged endpoints to ADMIN while complaint submission and the status chatbot stay PUBLIC. Inter-service calls (`UrbanDataClient`) now use the ADMIN key. |
| Guessing/knowing a complaint ID was enough to read another citizen's PII via the status chatbot | `ComplaintStatusChatService` now verifies the caller-supplied `citizenId` matches the complaint's actual owner; returns the same "not found" response either way so it doesn't leak existence. |
| Default secrets (`change-me-in-prod`) were functional, not placeholders that fail | `SecretsStartupCheck` refuses to boot under a `prod` Spring profile with unchanged defaults; logs a loud warning otherwise. |
| Network retry on complaint submission created a duplicate complaint | Optional client-supplied `idempotencyKey` (unique sparse Mongo index) — a repeated submission with the same key returns the original record instead of creating a second one. |
| No change history — "who marked this resolved, and when" was unanswerable | `CitizenComplaint.history[]` records every status/classification transition with an actor. |
| No audit trail for privileged writes | `X-Caller-Id` header logged (and, for status changes, recorded in `history[]`) on `PATCH /classification`, `PATCH /status`, `/cache/evict`. |
| `/actuator/health` didn't know if Kafka/the async pipeline was stalled | `management.health.kafka.enabled=true` — Spring Boot auto-provides this once Kafka + a `KafkaAdmin` bean exist, no custom code needed. Trade-off: `show-details:always` exposes it to the unauthenticated `/actuator/health` path (kept public for container/LB health checks). |
| Every service shared one Postgres superuser-equivalent account | Separate `traffic_app`/`ai_insight_app` roles (`infra/postgres-init/002-app-roles.sql`). **Honest scope note**: this is NOT full DDL/DML separation — both still need `CREATE` for their own auto-migration on first boot. Real value: neither service uses the actual bootstrap superuser, and a leaked credential for one doesn't grant access under the other's identity. |
| SLA escalation drafts sat behind a GET endpoint nobody polled | Optional Slack-compatible webhook delivery (`sla.webhook-url`) — best-effort, never blocks the sweep. |
| Zero automated tests despite several hardening rounds | Added JUnit5/Mockito unit tests for `ComplaintService` (heuristics + idempotency), `PromptSafetyUtils` (injection detection), `TrafficIngestionService` (anomaly scoring). No Spring context needed — pure logic tests, fast and dependency-free. |

Still deferred: Kafka SASL/TLS, a human-correction feedback loop for classification, photo-based severity scoring (the `photoUrls` field exists but nothing reads it — flagging this explicitly so its presence doesn't imply the feature works), and delivery integrations beyond the SLA webhook.

## Scalability fixes (round 6)

| Bottleneck | Fix |
|---|---|
| Resilience4j's `@RateLimiter` is per-JVM — run N replicas of ai-insight-service and the *effective* limit silently becomes N× what's configured | `DistributedRateLimiter` — a Redis fixed-window counter (`INCR`+`EXPIRE`) shared by every replica, applied via `RateLimitInterceptor`. One limit means one limit regardless of replica count. Fails **open** if Redis itself is down (a rate-limiter outage shouldn't become a full outage) — logged loudly when that happens. Trade-off: fixed windows allow a boundary burst up to ~2x the configured limit across two adjacent windows; acceptable for a cost-control backstop, not a precision SLA. |
| `TrafficIngestionService.ingest()` ran two Postgres aggregate queries (`AVG`/`STDDEV` over 24h) on **every single ingested reading** | Replaced with a Redis-maintained rolling window per sensor (bounded `LIST`, last 100 readings, 48h TTL) — mean/stddev computed in-process over ≤100 values, zero Postgres queries in the anomaly-scoring hot path. Postgres is now touched only for the actual persistence write. Semantic trade-off: baseline is now "last N readings" rather than "all readings in 24h" — similar in practice for steadily-reporting sensors, worth knowing if reporting frequency varies wildly. |
| No metrics on LLM latency/cost — the actual bottleneck in this system — despite generic HTTP metrics being free from Spring Boot | `LlmCallMetrics` (Micrometer `Timer` + success/failure `Counter` per call site) wired into all 12 LLM/embedding call sites across the codebase. Exposed at `/actuator/prometheus` as `urban_llm_call_seconds{call_site=...}` and `urban_llm_call_total{call_site=...,outcome=...}`. |
| **Found while wiring metrics**: none of the three services actually had `spring-boot-starter-actuator` on the classpath | All the `/actuator/*` config from earlier hardening rounds (health, circuitbreakers, etc.) was silently non-functional this whole time. Added the missing dependency to all three `pom.xml`s — this is why full `mvn compile` verification matters; it's the kind of gap that pure code review can miss. |
| No observability stack to make scaling decisions with data instead of guesses | Prometheus + Grafana added to `docker-compose.yml` (`infra/prometheus/prometheus.yml` scrapes all three services). Prometheus authenticates via `Authorization: Bearer <key>` — added as a fallback to `ApiKeyAuthFilter` alongside `X-API-Key`, since Prometheus's `bearer_token` scrape config is universally supported but a custom header isn't. |

Still deferred (documented, not implemented, given scope): a real Kafka cluster (≥3 brokers, proper replication — currently single-node with `acks=1`), Postgres table partitioning/retention policy for `sensor_readings`, Mongo sharding, Redis Cluster, service discovery / API gateway (hardcoded service URLs currently), and HPA on Kafka-lag rather than CPU. Access Grafana at `http://localhost:3000` (default admin/admin, see `GRAFANA_ADMIN_PASSWORD`) once `docker compose up` — no dashboards are pre-provisioned, only the Prometheus datasource-ready metrics; you'd add panels for `urban_llm_call_seconds`, `resilience4j_circuitbreaker_state`, and JVM/Kafka-consumer-lag metrics as a next step.

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
