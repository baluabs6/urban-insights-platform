-- Enables the pgvector extension used by ai-insight-service's persistent
-- embedding store (dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore).
-- The postgres official image doesn't ship pgvector by default; for local docker-compose
-- use the "pgvector/pgvector:pg16" image instead of "postgres:16" (see docker-compose.yml),
-- which already bundles the extension binary — this script just activates it.
CREATE EXTENSION IF NOT EXISTS vector;
