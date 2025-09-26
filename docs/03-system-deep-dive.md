# Time Series API System Deep Dive

This document walks through the full stack from the edge container that terminates TLS to the Spring Boot service, its data access layer, and the storage engines (TimescaleDB/PostgreSQL and OpenSearch). It complements the README by explaining how the pieces collaborate in production-like environments.

## 1. Edge Gateway (Express + TypeScript)

### 1.1 Container and Runtime
- Docker image is built from `gateway/` and used by the `ts-gateway` service in `ops/docker/compose.yml`.
- Exposes HTTPS on port 8443 internally and 443/8443 outside the container; an HTTP listener on port 8080 is available for optional redirects.
- Loads TLS key/cert from `/certs` (mounted from `../certs` in the repo). The certificate paths can be overridden with `SSL_KEY_PATH` and `SSL_CERT_PATH`.

### 1.2 Application Setup (`gateway/src/app.ts`)
- Uses Express with JSON body parsing (`express.json`), compression, and structured logging (`pino-http`).
- Hardens HTTP behaviour with Helmet. HSTS is configured to two years with `includeSubDomains` and `preload`. Additional middleware pins referrer policy, frameguard, CORP/COOP/COEP, and a restrictive `Permissions-Policy`.
- Rate limiting is enforced via `express-rate-limit` (120 requests per minute by default). Custom headers (`RateLimit`, `X-RateLimit-*`) are emitted for observability.
- CORS defaults to `origin: false`, effectively blocking cross-origin browsers unless explicitly configured.
- Authentication is optional: when `AUTH_ENABLED=true`, the gateway verifies JWTs with `express-jwt` and JWKS (via `jwks-rsa`). Legitimate admin actions require tokens possessing the configured scope (`ADMIN_SCOPE`) or role (`ADMIN_ROLE`/`ADMIN_ROLE_CLAIM`).
- Cookies are forcefully secured: a wrapper around `res.cookie` ensures `Secure`, `HttpOnly`, `SameSite=strict`, and `path='/'`. If an endpoint opts into `SameSite=None`, the middleware still toggles `Secure=true` to satisfy browser rules.
- TLS connections enforce at least TLS 1.2 (`minVersion: 'TLSv1.2'`).

### 1.3 Proxy Responsibilities
- Proxies root and health (`/`, `/health`, `/v1/ping`) to the Spring service.
- Forwards `/v1/series/*` endpoints, copying relevant headers (`Accept`, `If-None-Match`, `If-Modified-Since`) and caching `/v1/series/:id/data` responses in-memory with an optional TTL (`DATA_CACHE_TTL_MS`).
- Provides admin routes (`/v1/series/batch`, `/admin/reindex`) guarded by `requireAdmin` middleware.
- Adds tracing-friendly behaviour: For example `forwardAuthorization` keeps bearer tokens intact for downstream audit.

### 1.4 Deploy-Time Configuration
Environment variables (from `.env`) determine runtime behaviour:
- Port controls: `HTTP_PORT`, `HTTPS_PORT`, `PUBLIC_HTTPS_PORT`, `HTTP_MODE` (redirect|serve|off).
- Back-end routing: `JAVA_API_URL` (defaults to `http://localhost:8080`).
- Auth: `AUTH_ENABLED`, `OAUTH_JWKS_URI`, `OAUTH_AUDIENCE`, `OAUTH_ISSUER`, `ADMIN_SCOPE`, `ADMIN_ROLE`, `ADMIN_ROLE_CLAIM`.
- Cache tuning: `DATA_CACHE_TTL_MS`.

## 2. Spring Boot Service (Java 21)

### 2.1 Container and Runtime
- Built from `service/` as `ts-service` in Docker Compose.
- Uses environment variables from `.env` to connect to the TimescaleDB instance.
- Exposes HTTP on port 8080 and participates in `devnet` bridge network for container-to-container communication.

### 2.2 Application Configuration
- Gradle-based project targeting Java 21 with Spring Boot 3.5.x.
- Flyway runs database migrations from `service/src/main/resources/db/migration`.
- Core configuration lives in `application.yml`. Recent hardening sets `server-header: ""`, and session cookies are forced to `http-only`, `secure`, `same-site: strict`.

### 2.3 Security Stack (`SecurityConfig.java`)
- Provides two filter chains, toggled by `security.auth.enabled`:
  - **Authenticated mode**: configures the app as an OAuth2 resource server (JWT), requires JWTs for non-public endpoints, and sets stateless session policy.
  - **Open mode**: leaves endpoints unauthenticated (useful for local demos).
- Default headers are disabled and re-declared explicitly: frame DENY, HSTS (same values as gateway), `strict-origin-when-cross-origin` referrer policy, CORP/COOP/COEP alignment with the gateway, and `Permissions-Policy` forbidding geolocation/camera/microphone.
- Tomcat customiser strips `Server` and `X-Powered-By` headers while ensuring HttpOnly cookies.

### 2.4 REST Controllers
- **`RootController`** (`/`, `/v1/ping`): health endpoints (JSON banners) used by Compose health checks and the gateway.
- **`SeriesController`** (`/v1/series`):
  - `GET /{id}` loads metadata via `TimeSeriesService.getSeries`.
  - `GET /{id}/data` streams data points with resampling, transforms, fill policies, pagination, and ETags.
  - `GET /search` runs search queries, with optional sparse fieldsets.
  - `POST /batch` (admin required) triggers a full reindex of data into OpenSearch so the gateway exposes up-to-date search results.
- **`ExportsController`** (`POST /v1/exports`): stub for export workflows (returns an empty list but keeps the route live for future implementation).
- **`AdminController`** (`POST /admin/reindex`): admin-only reindex endpoint that delegates to `SeriesSyncService`.

### 2.5 Service Layer and Business Logic
- **`TimeSeriesService`** orchestrates the main data flow:
  - Validates parameters and fetches a `Series` entity (JPA) for metadata.
  - Pulls time-series observations via `SeriesDataDao` (JDBC Template).
  - Detects the dataset’s native frequency and resamples it using `Resampler` (`text/csv` friendly structure) if the client asks for weekly/monthly/quarterly/annual aggregation.
  - Applies statistical transforms (YoY, MoM, pct change, diff, etc.) and fill policies (forward/backward fill) through dedicated helper classes (e.g., `Transformer`, `Filler`).
  - Builds `SeriesDataResponse`, capturing metadata, pagination, tuples of `[date, value]`, and a deterministic ETag (MD5 of inputs).
- **`SeriesSearchService`** performs search:
  - Primary path hits OpenSearch (`opensearchproject/opensearch` container) with a `multi_match` query plus filters on country/frequency.
  - If OpenSearch is unavailable or empty, falls back to the relational repository query (`SeriesRepository.searchSeries`).
  - Provides bulk indexing to keep OpenSearch in sync; used during admin reindexing and future sync workflows.
- **`SeriesSyncService`** is an async orchestrator (leverages `@Async`) to reindex the catalog in batches. Individual `syncSeries` records are ready for event-driven updates, though not wired yet.

### 2.6 Data Access Layer
- **JPA Entities**: `Series` maps to the `series` table (primary metadata). Frequency is stored as a `char` (`A`, `Q`, `M`, `W`, `D`).
- **Spring Data Repository**: `SeriesRepository` extends `JpaRepository` and offers a custom JPQL `searchSeries` method with case-insensitive search and optional filters.
- **JDBC DAO**: `SeriesDataDao` executes tuned SQL for timeseries retrieval:
  - `fetchRange` returns the latest values out of `series_data` (hypertable) within the requested window.
  - `fetchRangeAsOf` supports historical replay by joining `series_data` with `series_data_history` using window functions, returning the state as of a cutoff timestamp.

### 2.7 Optimisation Touchpoints
- Resampling only stores the last observation per bucket for memory efficiency (LinkedHashMap to preserve order).
- Pagination calculations avoid heavy `LIMIT/OFFSET` logic by slicing in memory after transformations.
- ETags prevent redundant downstream processing when clients or the gateway re-request the same data slice.
- Bulk indexing to OpenSearch uses NDJSON batches of 500 records to balance memory usage and index throughput.

## 3. Storage Layer

### 3.1 TimescaleDB / PostgreSQL
- Provisioned via the `timescale/timescaledb:2.15.2-pg16` image with persistent volume `dbdata`.
- Flyway migrations:
  - `V1__bootstrap.sql`: placeholder to stabilise existing environments.
  - `V2__core_tables.sql`: creates `series` metadata table, `series_data` for current values, `series_data_history` for point-in-time queries, and converts `series_data` into a hypertable with yearly chunks and eight partitions by `series_id`.
  - `V3__add_series_description.sql`: adds `description` to the metadata table.
- Postgres settings: Compose mounts a custom `pg_hba.conf` and passes `listen_addresses=*` so other containers can connect.
- Health probe uses `pg_isready` with credentials from `.env`.

### 3.2 OpenSearch
- `opensearchproject/opensearch:2.14.0` runs as `ts-search` (single-node, security plugin disabled for simplicity).
- Stores documents for search results (fields: `series_id`, `name`, `description`, `geography`, `frequency`).
- On startup, `SeriesSearchService.ensureIndex()` checks for the index and creates it with a simple mapping if absent.
- Bulk ingest endpoint (`/_bulk`) is used for reindexing.

### 3.3 Optional Components
- Redis (`redis:7.2`) enabled via `--profile redis` for future caching or pub/sub extensions (not currently consumed by the app code).
- Jaeger (`jaegertracing/all-in-one`) behind the `jaeger` profile to visualise traces. Ports 16686 (UI), 4317/4318 (OTLP) are exposed.

## 4. Certificates and TLS Story
- Development certificates live in `certs/`. The Compose file mounts this directory into the gateway so HTTPS works locally without regenerating certs on every build.
- Production guidance is to swap them out for certificates managed by the platform (ACM, Let’s Encrypt, etc.) and keep only the private key on the host.
- Gateway enforces TLS by default; HTTP requests are 308-redirected to HTTPS when `HTTP_MODE=redirect` (the default in Compose).
- HSTS on both gateway and service means browsers will remember to prefer HTTPS.

## 5. Observability and Ops
- Health checks:
  - Gateway: `GET /health` (over HTTPS) is used by Docker Compose health probe.
  - Service: `GET /actuator/health` via wget/grep ensures Spring is UP before starting the gateway.
- Logging:
  - Gateway uses `pino-http` (structured logs). In Compose, logs go to stdout.
  - Service logs follow Spring Boot defaults; a dedicated request log is configured in `application.yml` (`logs/api-requests.log`).
- Rate limiting and caching reduce backend load and provide transparent headers for consumers to adjust behaviour.
- Admin reindexing is asynchronous so long-running jobs do not block client requests.

## 6. Security Summary
- Edge: Helmet headers, strict cookies, JWT validation, and optional admin role checks.
- Service: OAuth2 resource server capabilities, mirrored header hardening, stripped technology headers, secure session cookies (even though the API is effectively stateless), and consistent problem-details for errors.
- Database: Credentials sourced from environment variables; Compose stores them in `.env`. For production, secrets managers should replace plaintext env files.
- Network: Compose keeps containers on an isolated `devnet` bridge. In production, expect equivalent segmentation (private subnets, security groups).

## 7. Data Flow Recap
1. Client calls the gateway over HTTPS.
2. Gateway authenticates (optional), rate-limits, enforces headers, and proxies the request to the service.
3. Service controller delegates to the appropriate service class (`TimeSeriesService`, `SeriesSearchService`, etc.).
4. Data layer hits TimescaleDB or OpenSearch. For timeseries data, DAO fetches from hypertables/history; for search, OpenSearch responds or the repository fallback runs.
5. Business logic transforms/filters responses, adds ETags, and returns JSON (or CSV-compatible arrays) to the controller.
6. Gateway forwards the response, possibly caching `/v1/series/:id/data` for a short TTL and preserving ETag headers.

---

This deep dive should equip new contributors and operators with a mental model of the system’s moving parts—from certificates and edge hardening to data retrieval, search, and storage optimisation.