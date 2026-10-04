-- Enables the pgvector extension used by ai-insight-service's persistent
-- embedding store (Spring AI PgVectorStore, table urban_vector_store).
-- The postgres official image doesn't ship pgvector by default; for local docker-compose
-- use the "pgvector/pgvector:pg16" image instead of "postgres:16" (see docker-compose.yml),
-- which already bundles the extension binary — this script just activates it.
CREATE EXTENSION IF NOT EXISTS vector;
-- Spring AI's PgVectorStore schema initialisation also runs CREATE EXTENSION IF NOT EXISTS for these two.
-- Creating them here (as the bootstrap superuser) means the non-superuser ai_insight_app role never has to.
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
