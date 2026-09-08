-- Separate credentials per service, instead of every service sharing the
-- POSTGRES_USER superuser-equivalent bootstrap account (urban_user).
--
-- Honest scope note: this does NOT achieve full DDL/DML separation. Both
-- traffic-service (Hibernate ddl-auto=update) and ai-insight-service
-- (PgVectorEmbeddingStore createTable:true) need CREATE privilege on first
-- boot to auto-create their own tables — stripping CREATE would break
-- startup unless a real migration tool (Flyway/Liquibase) pre-creates exact
-- schemas for a fully DML-only runtime role, which this reference project
-- doesn't have. What this DOES achieve: neither service uses the actual
-- Postgres bootstrap superuser, and a leaked credential for one service
-- doesn't grant access under the other service's identity — real blast-radius
-- reduction, just not textbook least privilege.

DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'traffic_app') THEN
    CREATE ROLE traffic_app WITH LOGIN PASSWORD 'traffic_app_pass';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'ai_insight_app') THEN
    CREATE ROLE ai_insight_app WITH LOGIN PASSWORD 'ai_insight_app_pass';
  END IF;
END
$$;

GRANT CONNECT ON DATABASE urban_traffic TO traffic_app;
GRANT CONNECT ON DATABASE urban_traffic TO ai_insight_app;

GRANT USAGE, CREATE ON SCHEMA public TO traffic_app;
GRANT USAGE, CREATE ON SCHEMA public TO ai_insight_app;

-- Once each service's tables exist, grant the other role read access is
-- deliberately NOT done here — traffic_app has no reason to see
-- urban_embeddings and vice versa. Each role only needs rights on tables it
-- creates itself, which CREATE on the schema + being the table owner already
-- provides automatically in Postgres.

-- Neither role can create/drop other databases or other roles.
ALTER ROLE traffic_app NOSUPERUSER NOCREATEDB NOCREATEROLE;
ALTER ROLE ai_insight_app NOSUPERUSER NOCREATEDB NOCREATEROLE;
