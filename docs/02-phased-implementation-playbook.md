Below is a **phased, end‑to‑end implementation guide** you (or a coding agent) can follow to build the **Time Series Data API** with a **Java backend (Spring Boot)** and a **Node.js gateway (Express + TypeScript)**, ready to run locally and AWS‑ready. It **tracks the structure and decisions in your “Original Guide”** (endpoint design, quality attributes, security, performance posture) and turns them into a concrete, reproducible plan.

> **Why these tech choices?**
> *Java 21 (LTS) + Spring Boot 3* gives first‑class REST, security, metrics, and production operations (Actuator, Micrometer). *Node 22 (LTS)* works as a lightweight façade (API gateway/BFF) to enforce edge security policies (rate‑limit, auth, CORS, gzip) and to proxy to the Java service—mirroring CEIC’s frontend/gateway setup. Java 21 is an LTS release; Spring Boot auto‑wires metrics with Micrometer/Prometheus; Node 22 is supported and current; OpenTelemetry covers tracing for both tiers. ([Oracle][1])

---

## How to use this document

* **Follow the phases in order.** Each phase has **Done when** checks.
* **Only “why” comments** in code where non‑obvious (your requirement).
* **Security & quality attributes** (latency, scalability, resilience, cacheability, observability, etc.) are embedded in the tasks and referenced to your Original Guide sections.
* **Citations** back key decisions to primary docs (Spring, OWASP, Timescale, OpenTelemetry, etc.).

---

# Phase 0 — Repo skeleton, conventions & tooling

**Outcome:** a clean mono‑repo you can push to GitHub, batteries included for local dev, CI, and docs.

**0.1. Layout**

```
timeseries-api/
├─ gateway/              # Node.js facade (Express + TS)
├─ service/              # Java Spring Boot backend (core API)
├─ ops/
│  ├─ docker/            # Dockerfiles, compose, init SQL
│  └─ k6/                # load tests
├─ docs/                 # OpenAPI, ADRs, runbooks
├─ .editorconfig
├─ .gitattributes
├─ .gitignore            # include .env*, !.env.example
├─ LICENSE
└─ README.md
```

**0.2. Git & CI**

* Conventional commits (feat:, fix:), protected branches.
* GitHub Actions: Java build + tests, Node build + tests, Docker build, OpenAPI validation.
* Security scans (Dependabot, `npm audit`, `mvn versions:display-dependency-updates`).

**0.3. Env & config**

* `.env.example` at repo root; *never* commit `.env`.
* 12‑factor: all secrets via env or AWS Secrets Manager (later).
* Distinguish **generic JSON config** (e.g., search mappings) from **secrets**.

**Done when:** repo pushes cleanly; CI is green; `README` explains local bootstrap.

---

# Phase 1 — Local dev stack (Docker Compose)

**Outcome:** one command brings up Postgres+Timescale, OpenSearch, Redis (optional), Prometheus/Grafana, Jaeger (or OTLP collector), the Java service, and the Node gateway.

`ops/docker/compose.yml` (essentials):

```yaml
version: "3.9"
services:
  db:
    image: timescale/timescaledb:2.15.2-pg16
    environment:
      - POSTGRES_USER=ts
      - POSTGRES_PASSWORD=ts
      - POSTGRES_DB=tsdb
    ports: [ "5432:5432" ]
    volumes:
      - ./initdb:/docker-entrypoint-initdb.d

  search:
    image: opensearchproject/opensearch:2.14.0
    environment:
      - discovery.type=single-node
      - plugins.security.disabled=true
      - OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m
    ports: [ "9200:9200" ]

  prometheus:
    image: prom/prometheus
    ports: [ "9090:9090" ]
    volumes: [ "./prometheus.yml:/etc/prometheus/prometheus.yml" ]

  grafana:
    image: grafana/grafana
    ports: [ "3001:3000" ]

  otelcol:
    image: otel/opentelemetry-collector:latest
    ports: [ "4317:4317", "4318:4318" ] # OTLP gRPC/HTTP

  service:
    build: ../../service
    env_file: ../../.env
    depends_on: [ db, search, otelcol ]
    ports: [ "8080:8080" ]

  gateway:
    build: ../../gateway
    env_file: ../../.env
    depends_on: [ service ]
    ports: [ "8081:8081" ]
```

`ops/docker/initdb/00_timescale.sql`:

```sql
CREATE EXTENSION IF NOT EXISTS timescaledb;
```

We’ll convert `series_data` to a hypertable in Phase 3 for time‑range performance. ([MDN Web Docs][2])

**Done when:** `docker compose up` exposes:

* Java: `http://localhost:8080/actuator/health` ✔
* Node: `http://localhost:8081/health` ✔
* OpenSearch: `http://localhost:9200` ✔
* Prometheus/Grafana for metrics, OTLP collector for traces. (Spring Boot exposes Prometheus metrics via Actuator/Micrometer.) ([Home][3])

---

# Phase 2 — Java service scaffold (Spring Boot 3 + Java 21 LTS)

**Outcome:** Spring Boot API skeleton with security, metrics, OpenAPI, data access.

1. **Generate project** (Spring Initializr or manual):

**Dependencies:** Web, Validation, Data JPA, JDBC, PostgreSQL, Actuator, Security (OAuth2 Resource Server), Micrometer Prometheus, Resilience4j, Flyway, springdoc‑openapi.

> Java 21 is the current LTS; use the vendor JDK you prefer (Oracle, Temurin, Azul). ([Oracle][1])

`service/build.gradle.kts` (snippet):

```kotlin
plugins {
  id("org.springframework.boot") version "3.3.3"
  id("io.spring.dependency-management") version "1.1.5"
  kotlin("jvm") version "2.0.0" apply false // if not using Kotlin, omit
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("org.springframework.boot:spring-boot-starter-jdbc")
  implementation("org.postgresql:postgresql")

  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("io.micrometer:micrometer-registry-prometheus")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

  implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")
  implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")

  implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-csv") // CSV output

  runtimeOnly("org.flywaydb:flyway-core")

  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.testcontainers:postgresql")
}
```

`service/src/main/resources/application.yml` (core config):

```yaml
server:
  port: 8080

spring:
  threads:
    virtual:
      enabled: true   # Java 21 virtual threads for I/O heavy endpoints
  datasource:
    url: jdbc:postgresql://localhost:5432/tsdb
    username: ts
    password: ts
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 10000
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: none
  flyway:
    enabled: true
    locations: classpath:db/migration

management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus,info
  endpoint:
    health:
      probes:
        enabled: true

# For OAuth2 resource server (optional in local)
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${OAUTH_JWKS_URI:http://localhost:8082/.well-known/jwks.json}
```

* **Micrometer/Prometheus**: `/actuator/prometheus` auto‑exposed when registry is on classpath. ([Micrometer Documentation][4])
* **Virtual threads** improve concurrency in I/O bound workloads in Boot 3.3 with `spring.threads.virtual.enabled`. ([Home][5])
* **HikariCP** properties sized conservatively; tune later per DB CPU. (Hikari is default in Boot 3.) ([Baeldung on Kotlin][6])

**Done when:** `./gradlew bootRun` serves `/actuator/health`, `/v3/api-docs`, `/swagger-ui.html` (springdoc). ([Node.js][7])

---

# Phase 3 — Schema & migrations (Flyway + Timescale hypertable)

**Outcome:** normalized metadata + time‑series tables, point‑in‑time (revision) support, and a hypertable for performant ranges.

`service/src/main/resources/db/migration/V1__core_tables.sql`:

```sql
CREATE TABLE series (
  series_id     VARCHAR(128) PRIMARY KEY,
  name          TEXT NOT NULL,
  frequency     CHAR(1) NOT NULL,  -- A/Q/M/W/D
  unit          TEXT,
  geography     VARCHAR(16),
  source        TEXT,
  is_adjusted   BOOLEAN DEFAULT FALSE,
  start_date    DATE,
  end_date      DATE,
  last_update   TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE TABLE series_data (
  series_id     VARCHAR(128) NOT NULL,
  ts_date       DATE NOT NULL,
  value         DOUBLE PRECISION,
  PRIMARY KEY (series_id, ts_date),
  FOREIGN KEY (series_id) REFERENCES series(series_id)
);

-- Point-in-time history (latest values live in series_data)
CREATE TABLE series_data_history (
  series_id      VARCHAR(128) NOT NULL,
  ts_date        DATE NOT NULL,
  value          DOUBLE PRECISION,
  revision_time  TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (series_id, ts_date, revision_time),
  FOREIGN KEY (series_id) REFERENCES series(series_id)
);

-- Convert to hypertable for range scans:
SELECT create_hypertable('series_data', 'ts_date', if_not_exists => TRUE);
-- Optionally space partition by series_id for very large datasets:
-- SELECT create_hypertable('series_data', 'ts_date', chunk_time_interval => INTERVAL '1 year',
--                          partitioning_column => 'series_id', number_partitions => 8, if_not_exists => TRUE);
```

* Hypertables provide fast time‑range queries and compression—proven for time‑series data. ([MDN Web Docs][2])

**Revision (as\_of) query patterns:**

* Latest as of a cutoff: window function or `DISTINCT ON`. Example (“pick latest revision ≤ \:asOf”):

```sql
SELECT sd.series_id, sd.ts_date, sd.value
FROM (
  SELECT series_id, ts_date, value,
         ROW_NUMBER() OVER (PARTITION BY series_id, ts_date
                            ORDER BY revision_time DESC NULLS LAST) AS rn
  FROM (
    SELECT series_id, ts_date, value, null::timestamp AS revision_time
    FROM series_data
    WHERE series_id = :id AND ts_date BETWEEN :start AND :end
    UNION ALL
    SELECT series_id, ts_date, value, revision_time
    FROM series_data_history
    WHERE series_id = :id AND ts_date BETWEEN :start AND :end AND revision_time <= :asOf
  ) u
) x
WHERE rn = 1
ORDER BY ts_date;
```

* Or `DISTINCT ON (ts_date) ... ORDER BY ts_date, revision_time DESC`. ([Testcontainers for Java][8])

**Done when:** Flyway migrates cleanly; `EXPLAIN` shows index range scans on `(series_id, ts_date)`.

---

# Phase 4 — Data model & repositories (JPA for metadata, JDBC for hot paths)

**Outcome:** clean entities + efficient data reads.

**Entities (examples, Java records OK where useful):**

```java
// service/src/main/java/app/series/Series.java
@Entity @Table(name = "series")
public class Series {
  @Id private String seriesId;
  private String name;
  private char frequency;      // 'A','Q','M','W','D'
  private String unit;
  private String geography;
  private String source;
  private boolean isAdjusted;
  private LocalDate startDate;
  private LocalDate endDate;
  private Instant lastUpdate;
  // getters...
}

// Value object for data point (no JPA mapping needed on hot path)
public record DataPoint(LocalDate date, Double value) {}
```

**Repositories:**

```java
@Repository
public interface SeriesRepository extends JpaRepository<Series, String> {}

@Repository
public class SeriesDataDao {
  private final JdbcTemplate jdbc;

  public SeriesDataDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public List<DataPoint> fetchRange(String id, LocalDate start, LocalDate end) {
    String sql = """
      SELECT ts_date, value
      FROM series_data
      WHERE series_id = ? AND ts_date BETWEEN ? AND ?
      ORDER BY ts_date
    """;
    return jdbc.query(sql, (rs, i) -> new DataPoint(rs.getDate(1).toLocalDate(),
                                                    (Double)rs.getObject(2)),
                      id, start, end);
  }

  public List<DataPoint> fetchRangeAsOf(String id, LocalDate start, LocalDate end, Instant asOf) {
    String sql = """
      SELECT ts_date, value
      FROM (
        SELECT ts_date, value,
               ROW_NUMBER() OVER (PARTITION BY ts_date ORDER BY revision_time DESC NULLS LAST) rn
        FROM (
          SELECT ts_date, value, NULL::TIMESTAMPTZ AS revision_time
          FROM series_data WHERE series_id = ? AND ts_date BETWEEN ? AND ?
          UNION ALL
          SELECT ts_date, value, revision_time
          FROM series_data_history
          WHERE series_id = ? AND ts_date BETWEEN ? AND ? AND revision_time <= ?
        ) u
      ) x
      WHERE rn = 1
      ORDER BY ts_date
    """;
    return jdbc.query(sql, (rs, i) -> new DataPoint(rs.getDate(1).toLocalDate(),
                                                    (Double)rs.getObject(2)),
      id, start, end, id, start, end, Timestamp.from(asOf));
  }
}
```

* **Why JDBC here?** It streams light DTOs in order and avoids ORM overhead in hot loops (per your performance guidance).

**Done when:** metadata reads are JPA, data reads are JDBC; queries return ordered points.

---

# Phase 5 — Business layer (resample, transform, fill) with data‑oriented kernels

**Outcome:** table‑driven transforms with single‑pass loops, minimal allocation.

```java
public enum Frequency { NATIVE, D, W, M, Q, A }
public enum Transform { AS_IS, YOY, MOM, PCT_CHANGE, YTD, DIFF }
public enum FillPolicy { NONE, FFILL, BFILL }

public final class Resampler {
  public static List<DataPoint> resample(List<DataPoint> in, Frequency from, Frequency to) {
    if (to == Frequency.NATIVE || to == from) return in;
    // Example: M->Q by taking last observation in quarter (can be strategy-based)
    Map<LocalDate, Double> buckets = new LinkedHashMap<>();
    for (DataPoint p : in) {
      LocalDate bucket = bucketDate(p.date(), to);
      buckets.put(bucket, p.value()); // overwrite -> last in period
    }
    List<DataPoint> out = new ArrayList<>(buckets.size());
    for (var e : buckets.entrySet()) out.add(new DataPoint(e.getKey(), e.getValue()));
    return out;
  }
  // bucketDate(...) maps date to W/M/Q/A start/end as required
}

public final class Transformer {
  public static List<DataPoint> apply(List<DataPoint> in, Transform t, Frequency f) {
    if (t == Transform.AS_IS) return in;
    List<DataPoint> out = new ArrayList<>(in.size());
    switch (t) {
      case DIFF -> {
        Double prev = null;
        for (var p : in) {
          Double v = (prev == null || p.value()==null) ? null : p.value() - prev;
          out.add(new DataPoint(p.date(), v));
          prev = p.value();
        }
      }
      case PCT_CHANGE, MOM, YOY, YTD -> { /* similar single-pass kernels; YOY uses lag by 12/4/1 */ }
      default -> { return in; }
    }
    return out;
  }
}

public final class Filler {
  public static List<DataPoint> fill(List<DataPoint> in, FillPolicy policy) {
    if (policy == FillPolicy.NONE) return in;
    List<DataPoint> out = new ArrayList<>(in.size());
    if (policy == FillPolicy.FFILL) {
      Double last = null;
      for (var p : in) {
        last = (p.value() != null) ? p.value() : last;
        out.add(new DataPoint(p.date(), last));
      }
      return out;
    }
    // BFILL
    Double next = null;
    for (int i=in.size()-1; i>=0; --i) {
      var p = in.get(i);
      next = (p.value() != null) ? p.value() : next;
      out.add(new DataPoint(p.date(), next));
    }
    Collections.reverse(out);
    return out;
  }
}
```

* **Performance note:** closed sets → `switch` enables inlining and branch‑friendly loops (fits your “data‑oriented > switch > OOP” preference).

**Done when:** unit tests prove correctness for all transforms and edge cases (missing, first/last lags).

---

# Phase 6 — REST controllers, errors, content‑negotiation, and ETags

**Outcome:** endpoints `/v1/series/search`, `/v1/series/{id}`, `/v1/series/{id}/data`, `/v1/series/batch`, `/v1/exports`.

```java
@RestController
@RequestMapping("/v1/series")
@Validated
public class SeriesController {
  private final TimeSeriesService svc;

  @GetMapping("/{id}")
  public SeriesDto get(@PathVariable String id) { return svc.getSeries(id); }

  @GetMapping("/{id}/data")
  public ResponseEntity<?> data(@PathVariable String id,
      @RequestParam(required = false) LocalDate start,
      @RequestParam(required = false) LocalDate end,
      @RequestParam(required = false, name="as_of") LocalDate asOf,
      @RequestParam(defaultValue="native") String freq,
      @RequestParam(defaultValue="as_is") String transform,
      @RequestParam(defaultValue="none") String fill,
      @RequestHeader(value="Accept", required=false) String accept) {

    var resp = svc.getData(id, start, end, asOf, freq, transform, fill);
    String etag = svc.computeEtag(id, start, end, asOf, freq, transform, fill); // e.g., hash(lastUpdate + params)

    return ResponseEntity.ok()
      .eTag(etag)
      .contentType(selectMediaType(accept)) // application/json by default
      .body(resp); // JSON by default; if text/csv → service writes CSV via Jackson CSV
  }
}
```

* Register `ShallowEtagHeaderFilter` to add transparent ETags (or compute domain ETag as above). ETags enable conditional GETs (`If-None-Match` → `304`). ([Home][9])
* For CSV output, use `jackson-dataformat-csv` with a custom `HttpMessageConverter` or direct stream. ([javadoc.io][10])

**Error model:** consistent JSON problem detail (400 invalid param, 404, 401/403, 429, 5xx). (Aligns with “Handling errors” section in your Original Guide.)
**Versioning:** URLs prefixed `/v1` (see “Tips for versioning” in your Original Guide and Apigee’s design book). ([Resilience4j][11])

**Done when:** endpoints respond in JSON; CSV works via `Accept: text/csv`; ETag/304 is honored.

---

# Phase 7 — Search: OpenSearch index + query

**Outcome:** `GET /v1/series/search?q=...&country=BR&freq=Q&page=1&page_size=50`.

**Index mapping (store fields you need to render results):**

```json
PUT /series_v1
{ "mappings": {
    "properties": {
      "series_id": { "type": "keyword" },
      "name":      { "type": "text"    },
      "description": { "type":"text" },
      "geography": { "type": "keyword" },
      "frequency": { "type": "keyword" }
}}}
```

**Query (multi\_match with filters):**

```json
POST /series_v1/_search
{
  "query": {
    "bool": {
      "must": [ { "multi_match": { "query": "real gdp", "fields": ["name^2", "description"] } } ],
      "filter": [
        { "term": { "geography": "BR" } },
        { "term": { "frequency": "Q" } }
] } },
  "from": 0, "size": 50
}
```

Multi‑match is standard in OpenSearch/Elasticsearch for relevancy across fields. ([OpenAPI 3 Library for spring-boot][12])

**Done when:** search returns `series_id` + summary; Java service hydrates full metadata from DB as needed.

---

# Phase 8 — Node.js gateway (Express + TypeScript)

**Outcome:** edge‑layer enforcing HTTPS‑only, rate‑limits, authn, gzip, CORS, and proxying to the Java service.

**Node 22 LTS** recommended for modern features & TS loader. (Node release schedule marks v22 as LTS.)
**Key middlewares:** `helmet` (security headers), `express-rate-limit` (429), `cors`, `compression`, `pino-http` (fast structured logs). ([OWASP Foundation][13])

`gateway/package.json` (snippet):

```json
{
  "type": "module",
  "scripts": {
    "dev": "tsx src/app.ts",
    "build": "tsc -p tsconfig.json",
    "start": "node dist/app.js"
  },
  "dependencies": {
    "axios": "^1.7.7",
    "compression": "^1.7.4",
    "cors": "^2.8.5",
    "express": "^4.19.2",
    "express-rate-limit": "^7.1.5",
    "helmet": "^7.1.0",
    "pino-http": "^9.0.0"
  },
  "devDependencies": {
    "tsx": "^4.16.0",
    "typescript": "^5.5.4"
  }
}
```

`gateway/src/app.ts`:

```ts
import express from 'express';
import helmet from 'helmet';
import rateLimit from 'express-rate-limit';
import compression from 'compression';
import cors from 'cors';
import pinoHttp from 'pino-http';
import axios from 'axios';

const app = express();
const JAVA_API = process.env.JAVA_API_URL || 'http://localhost:8080';
const limiter = rateLimit({ windowMs: 60_000, max: 120, standardHeaders: true, legacyHeaders: false });

app.disable('x-powered-by');
app.use(helmet());
app.use(cors({ origin: false }));          // locked-down; tune per UI host
app.use(compression());
app.use(express.json({ limit: '256kb' }));
app.use(limiter);
app.use(pinoHttp());

// Simple API-key auth (replace with JWT validation if needed)
app.use((req, res, next) => {
  const key = req.header('x-api-key');
  if (!key || key !== process.env.API_KEY) return res.status(401).json({ error: 'Unauthorized' });
  next();
});

app.get('/health', (_, res) => res.json({ ok: true }));

// Proxy example: series data
app.get('/v1/series/:id/data', async (req, res) => {
  try {
    const r = await axios.get(`${JAVA_API}/v1/series/${encodeURIComponent(req.params.id)}/data`,
      { params: req.query, headers: { Accept: req.headers.accept ?? 'application/json' } });
    // Mirror ETag/304, cache headers etc.
    if (r.headers.etag) res.setHeader('ETag', r.headers.etag);
    res.status(r.status).send(r.data);
  } catch (e:any) {
    if (e.response) res.status(e.response.status).send(e.response.data);
    else res.status(502).json({ error: 'Bad Gateway' });
  }
});

app.listen(8081, () => console.log('Gateway listening on :8081'));
```

* **Helmet** sets safe defaults for headers; **rate‑limit** returns 429; **compression** enables gzip/deflate; **CORS** as needed for browser clients. ([OWASP Foundation][13])

**Done when:** gateway enforces auth and rate‑limit; proxies `/v1/...` to Java; logs JSON with request ids.

---

# Phase 9 — Security hardening (OWASP + Spring Security + secrets)

**Outcome:** HTTPS‑only, least privilege, validated input, secrets management, DDoS posture.

* **HTTPS‑only endpoints** (terminate TLS at ALB/CloudFront in AWS; HSTS at edge). Enforce via infra and `helmet`. OWASP recommends HTTPS only for REST APIs. ([OWASP Cheat Sheet Series][14])
* **AuthN/AuthZ**

  * **Option A (quick):** API key at gateway (per client).
  * **Option B (preferred):** OAuth2 Client Credentials → gateway validates JWT (or pass‑through to resource server). Spring Boot Resource Server validates JWT via JWKS URI. ([Home][15])
* **Input validation**: Spring `@Validated`, regex for `seriesId`, explicit enum mapping for `freq/transform/fill`.
* **Database principle of least privilege**: dedicated DB user with SELECT only for API.
* **Secrets**: no secrets in code; for AWS use Secrets Manager/SSM; for local `.env`.
* **Rate limiting & throttling** at gateway; quotas optional.
* **DDoS posture**: front with CloudFront + AWS WAF; consider AWS Shield (Std/Advanced) for L3/4/7. ([AWS Documentation][16])
* **OWASP Top 10/API Security**: review cheat sheets for auth, input validation, error handling, logging. ([OWASP Cheat Sheet Series][17])

**Done when:** unauthenticated calls are rejected; rate‑limited; TLS termination set in infra profile.

---

# Phase 10 — Observability (metrics, logs, traces)

**Outcome:** consistent metrics/logs/traces across gateway and service.

* **Service (Java)**: Actuator + Prometheus registry → `/actuator/prometheus`; default HTTP & DB metrics via Micrometer. Export to Prometheus in local; alert in Grafana later. ([Home][3])
* **Gateway (Node)**: `pino-http` for structured logs; include correlation id (`X-Request-Id`). Pino is very fast. ([GitHub][18])
* **Traces**: OpenTelemetry auto‑instrumentation for **Java** and **Node**; export to local OTLP collector. Use resource attributes `service.name` = `ts-service` / `ts-gateway`. ([OpenTelemetry][19])

**Done when:** you can see request traces spanning gateway → service → DB, metrics exposed, structured logs with IDs.

---

# Phase 11 — Search sync job

**Outcome:** keep OpenSearch in sync with `series` metadata.

* On data load/update, publish to a small **sync component** (can be a Spring `@Async` job or Debezium / CDC later).
* For demo, provide a CLI endpoint `/admin/reindex` that streams all `series` to OpenSearch bulk API.

**Done when:** search finds newly added series.

---

# Phase 12 — Bulk export jobs (async + S3 presigned URLs)

**Outcome:** `POST /v1/exports` creates a job → client polls → gets `download_url`.

* **Tables**: `export_job(id, status, params_json, created_at, completed_at, location)`.
* **Worker**: Spring `@Async` (or scheduler) pulls pending jobs, streams CSV to local disk or S3, updates status, returns **presigned URL** for download. ([AWS Documentation][20])

> Spring Boot’s async/scheduling abstractions are built‑in (TaskExecutor/Scheduler). In Boot 3.3 you can even run them on virtual threads for I/O heavy jobs. ([Home][21])

**Done when:** large data requests do not tie up HTTP; clients download via finite‑lifetime URL.

---

# Phase 13 — OpenAPI, DX and docs

**Outcome:** a discoverable API developers love (Original Guide + Apigee book best practices).

* Add OpenAPI annotations and serve `/swagger-ui.html` with **springdoc‑openapi**. Provide examples, error schemas, and link to **rate limit** headers. ([Node.js][7])
* Host docs at the gateway root (static + reverse‑proxy to Java UI).
* Follow Apigee’s **Web API Design** guidance: *nouns, plural resources, versioning, error clarity, pagination, partial responses*. ([Resilience4j][11])

**Done when:** spec is complete and accurate; SDKs can be generated.

---

# Phase 14 — Testing strategy (unit, integration, contract, and load)

* **Unit**: resampler/transform/fill kernels; parameter validation (bad freq/transform → 400).
* **Integration**: **Testcontainers** for Postgres to exercise SQL exactly as production (no H2). ([Testcontainers][22])
* **Search**: mock OpenSearch client in service tests; add one end‑to‑end test against a dev OpenSearch container if desired.
* **Contract**: validate responses against OpenAPI in tests.
* **Load**: add `k6` scenarios under `ops/k6` (concurrency, cache hits, search bursts).

**Done when:** CI runs unit + integration with containers; load tests have baseline SLIs.

---

# Phase 15 — Performance posture (DB, pools, caching)

* **Queries**: push down filters; ensure ordered range scans; verify with `EXPLAIN ANALYZE`.
* **Timescale**: hypertable chunk interval \~1y (or auto) for M/Q/A series; consider space partition by `series_id` for very large catalog. ([MDN Web Docs][2])
* **Connection pool**: Start small (≤ CPU cores) and scale; tune `maximum-pool-size`, `connection-timeout`, `minimum-idle`. Use Hikari guidance. ([Baeldung on Kotlin][6])
* **Caching**:

  * HTTP **ETag**/conditional GET for `/series/{id}` and `/data` (Latest). ([Wikipedia][23])
  * Gateway micro‑cache (e.g., 30–60s) for hot indicators if acceptable freshness.
  * Redis for distributed cache (optional).

**Done when:** P95 latency meets target; DB is not the bottleneck under expected QPS.

---

# Phase 16 — AWS readiness

* **Compute**: ECS/Fargate or EKS; one service per container (gateway & service).
* **DB**: Amazon RDS for PostgreSQL; Timescale via self‑managed EC2 or Timescale Cloud.
* **Edge**: CloudFront + WAF; **AWS Shield** (Std; Advanced if needed) for DDoS. ([AWS Documentation][16])
* **Observability**: OTEL → ADOT collector → CloudWatch/Prometheus/Grafana.
* **Secrets**: AWS Secrets Manager; IAM roles for tasks (no long‑lived keys).

**Done when:** infra is codified (Terraform) and images are built/pushed.

---

# Phase 17 — Quality gates & runbooks

* **SLIs/SLOs**: latency (<200ms P95 typical), error rate, availability, search success.
* **Runbooks**: DB failover, search outage fallback, cache poisoning, rate‑limit tuning, hot fix.

**Done when:** on‑call can diagnose using metrics/logs/traces & documented steps.

---

## Code & configuration details (snippets to drop in)

### 1) Spring Security (JWT resource server) – optional local off

```java
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  @Bean
  SecurityFilterChain http(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
       .authorizeHttpRequests(auth -> auth
         .requestMatchers("/actuator/**","/v3/api-docs/**","/swagger-ui/**").permitAll()
         .anyRequest().authenticated()
       )
       .oauth2ResourceServer(oauth2 -> oauth2.jwt());
    return http.build();
  }
}
```

Spring Resource Server validates JWT from `jwk-set-uri`. ([Home][15])

### 2) CSV output (MessageConverter)

```java
@Configuration
public class CsvConfig implements WebMvcConfigurer {
  @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
    converters.add(new MappingJackson2CsvHttpMessageConverter());
  }
}

public class MappingJackson2CsvHttpMessageConverter extends AbstractHttpMessageConverter<Object> {
  private final CsvMapper mapper = new CsvMapper();
  public MappingJackson2CsvHttpMessageConverter() { super(MediaType.valueOf("text/csv")); }
  @Override protected boolean supports(Class<?> clazz) { return true; }
  @Override protected Object readInternal(Class<?> clazz, HttpInputMessage input) { throw new UnsupportedOperationException(); }
  @Override protected void writeInternal(Object o, HttpOutputMessage output) throws IOException {
    // serialize DTO with CsvMapper/CsvSchema
    var schema = CsvSchema.builder().setUseHeader(true).build();
    var gen = mapper.writer(schema).writeValueAsString(o);
    output.getBody().write(gen.getBytes(StandardCharsets.UTF_8));
  }
}
```

(Jackson CSV API provides `CsvMapper`/`CsvSchema`.) ([javadoc.io][10])

### 3) ETag support

Register Spring’s `ShallowEtagHeaderFilter` bean or compute domain ETags as shown. (Enables 304 with `If-None-Match`.) ([Home][9])

### 4) Rate‑limiting headers

Have the gateway add `RateLimit-Remaining` or `X-RateLimit-*` and mirror 429 with `Retry-After`. (Aligns with your “Throughput Management” & “Tips for handling exceptional behavior”.)

### 5) OpenTelemetry bootstrap

**Java** (agent, no code changes): run service with
`-javaagent:/opt/opentelemetry-javaagent.jar -Dotel.exporter.otlp.endpoint=http://otelcol:4317 -Dotel.service.name=ts-service`
OpenTelemetry Java agent auto‑instruments common libs. ([GitHub][24])

**Node** (basic setup; TypeScript): initialize SDK and Express instrumentation per OpenTelemetry docs. ([OpenTelemetry][25])

---

## Rich, efficient search queries

* Use OpenSearch **multi\_match** with field boosts and keyword filters (country/freq). Keep index with only what you need to show results fast; hydrate details from DB. ([OpenAPI 3 Library for spring-boot][12])

---

## Security checklist (expanded from Original Guide)

* TLS v1.2+ everywhere; HSTS at edge. ([OWASP Cheat Sheet Series][14])
* AuthN (JWT/OAuth2 CC) and API keys (gateway) with rotation.
* AuthZ (entitlements per series if needed).
* Input validation & length limits; JSON parse limits.
* SQL safety: prepared statements only.
* **Rate limiting, quotas, burst control** at gateway.
* **DDoS**: CloudFront + WAF + AWS Shield Std/Advanced. ([AWS Documentation][16])
* Secrets in env/manager; least‑privilege IAM/DB roles.
* Audit/Access logs (P.I.I. scrubbing).
* OWASP API Top 10 & REST cheat sheets as review gates. ([API Security News][26])

---

## Quality attributes (how the plan satisfies them)

* **Latency & Throughput:** Hypertable scans, tight JDBC loops, small DTOs, ETags, gzip, micro‑caching. ([MDN Web Docs][2])
* **Scalability:** stateless services (scale‑out); DB read replicas; search offloads text queries.
* **Reliability/Resilience:** timeouts, retries, circuit breakers (Resilience4j), bulkheads (separate pools), health probes. ([OpenSearch Documentation][27])
* **Observability:** Micrometer/Prometheus, structured logs, OpenTelemetry tracing end‑to‑end. ([Home][3])
* **Cacheability:** conditional GETs with ETag for metadata/data; client and CDN leverage. ([Wikipedia][23])
* **DX/Usability:** RESTful nouns, versioned paths, consistent errors, OpenAPI + Swagger UI. ([Resilience4j][11])
* **Testability:** Testcontainers for DB‑true integration tests. ([Testcontainers][22])
* **Security:** OWASP guidance, HTTPS‑only, rate‑limit, JWT/API keys, WAF/Shield. ([OWASP Cheat Sheet Series][14])

---

## Phase‑by‑phase “Done” checklist (quick view)

1. **Repo & CI**: mono‑repo created, CI pipeline builds & tests.
2. **Local stack**: `docker compose up` runs db/search/service/gateway/metrics/traces.
3. **Migrations**: Flyway creates tables + hypertable; sample data loads.
4. **Data access**: JPA for series, JDBC for data; as\_of query passes tests.
5. **Kernels**: resample/transform/fill single‑pass loops tested.
6. **Endpoints**: `/v1/series`, `/v1/series/{id}`, `/v1/series/{id}/data`, batch and exports (skeleton).
7. **Search**: OpenSearch index + `multi_match`; Java hydrates results.
8. **Gateway**: Express+TS with helmet, rate‑limit, gzip, CORS, pino; proxy in place. ([OWASP Foundation][13])
9. **Security**: auth on; rate‑limit returns 429; secrets externalized.
10. **Observability**: metrics visible, traces across tiers, logs structured.
11. **Bulk exports**: async job produces CSV and returns presigned URL. ([AWS Documentation][20])
12. **Docs**: OpenAPI + Swagger UI; examples & error schema complete.
13. **Tests**: unit + integration (Testcontainers), smoke load (k6).
14. **Perf & ops**: Hikari tuned, ETag/caching enabled, runbooks written.

---

## Appendix — alignment with your Original Guide

* **API surface & semantics**: `/v1/series/search`, `/v1/series/{id}`, `/v1/series/{id}/data?start&end&as_of&freq&transform&fill&format=csv`, `/v1/series/batch`, `/v1/exports` — same resources & query semantics (freq conversion, transforms, fill).
* **Design principles**: **nouns**, **plural collections**, **versioning**, **error contracts**, **pagination**, **conditional GET** with ETag — as in “Web API Design: Crafting Interfaces that Developers Love.” ([Resilience4j][11])
* **Performance posture**: DB keys, hypertable, table‑driven kernels, small DTOs, edge caching—matching your “data‑oriented first” guidance.
* **Security**: expanded checklist (HTTPS‑only, rate limit, TLS, authn/z, secrets, OWASP Top 10/API Security). ([OWASP Cheat Sheet Series][14])
* **Observability & Ops**: Actuator/Micrometer/Prometheus; OpenTelemetry; structured logs; SLOs; runbooks.

---

### Notes on tuning HikariCP

Start small for connection pool size and grow with evidence; follow Hikari’s guidance and measure with DB CPU and wait events. Boot 3 uses Hikari by default; you tune `maximum-pool-size`, `connection-timeout`, etc. ([Baeldung on Kotlin][6])

---

## What to demo in 30 minutes

1. Run `docker compose up` → show health endpoints, `/swagger-ui.html`.
2. `GET /v1/series/search?q=gdp&country=BR&freq=Q` → from OpenSearch.
3. `GET /v1/series/BR.GDP.REAL.Q.SA/data?transform=yoy&fill=ffill` → transformed values.
4. Show **ETag** round‑trip with `If-None-Match` → `304`. ([Wikipedia][23])
5. Turn on Prometheus & Grafana dashboard. ([Micrometer Documentation][4])
6. Show **trace** from gateway → service → DB in the OTEL UI. ([OpenTelemetry][19])
7. Trigger a bulk export → watch job complete → download via **presigned URL**. ([AWS Documentation][20])

---

If you’d like, I can expand any phase into copy‑paste **full files** (e.g., `docker-compose.yml`, full `build.gradle.kts`, `SecurityConfig`, CSV converter, OpenSearch index scripts, Testcontainers tests) or tailor the **as\_of** SQL to your exact revision semantics.

[1]: https://www.oracle.com/java/technologies/java-se-support-roadmap.html?utm_source=chatgpt.com "Java SE Support Roadmap"
[2]: https://developer.mozilla.org/en-US/blog/securing-apis-express-rate-limit-and-slow-down/?utm_source=chatgpt.com "Securing APIs: Express rate limit and slow down - MDN"
[3]: https://docs.spring.io/spring-boot/reference/actuator/metrics.html?utm_source=chatgpt.com "Metrics :: Spring Boot"
[4]: https://docs.micrometer.io/micrometer/reference/implementations/prometheus.html?utm_source=chatgpt.com "Micrometer Prometheus"
[5]: https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html?utm_source=chatgpt.com "Task Execution and Scheduling :: Spring Boot"
[6]: https://www.baeldung.com/spring-boot-hikari?utm_source=chatgpt.com "Configuring a Hikari Connection Pool with Spring Boot"
[7]: https://nodejs.org/en/blog/announcements/v22-release-announce?utm_source=chatgpt.com "Node.js v22"
[8]: https://java.testcontainers.org/modules/databases/postgres/?utm_source=chatgpt.com "Postgres Module - Testcontainers for Java"
[9]: https://docs.spring.io/spring-boot/documentation.html?utm_source=chatgpt.com "Documentation Overview :: Spring Boot"
[10]: https://www.javadoc.io/doc/com.fasterxml.jackson.dataformat/jackson-dataformat-csv/2.5.3/com/fasterxml/jackson/dataformat/csv/CsvSchema.html?utm_source=chatgpt.com "CsvSchema (Jackson-dataformat-CSV 2.5.3 API)"
[11]: https://resilience4j.readme.io/docs/getting-started-3?utm_source=chatgpt.com "Getting Started"
[12]: https://springdoc.org/?utm_source=chatgpt.com "OpenAPI 3 Library for spring-boot"
[13]: https://owasp.org/Top10/?utm_source=chatgpt.com "OWASP Top 10:2021"
[14]: https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html?utm_source=chatgpt.com "REST Security - OWASP Cheat Sheet Series"
[15]: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/filter/ShallowEtagHeaderFilter.html?utm_source=chatgpt.com "Class ShallowEtagHeaderFilter"
[16]: https://docs.aws.amazon.com/waf/latest/developerguide/ddos-overview.html?utm_source=chatgpt.com "How AWS Shield and Shield Advanced work"
[17]: https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html?utm_source=chatgpt.com "Authentication - OWASP Cheat Sheet Series"
[18]: https://github.com/pinojs/pino-http?utm_source=chatgpt.com "pinojs/pino-http: 🌲 high-speed HTTP logger for Node.js"
[19]: https://opentelemetry.io/docs/languages/java/getting-started/?utm_source=chatgpt.com "Getting Started by Example"
[20]: https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html?utm_source=chatgpt.com "Download and upload objects with presigned URLs"
[21]: https://docs.spring.io/spring-framework/reference/integration/scheduling.html?utm_source=chatgpt.com "Task Execution and Scheduling :: Spring Framework"
[22]: https://testcontainers.com/guides/getting-started-with-testcontainers-for-java/?utm_source=chatgpt.com "Getting started with Testcontainers for Java"
[23]: https://en.wikipedia.org/wiki/HTTP_ETag?utm_source=chatgpt.com "HTTP ETag"
[24]: https://github.com/open-telemetry/opentelemetry-java-instrumentation?utm_source=chatgpt.com "open-telemetry/opentelemetry-java-instrumentation"
[25]: https://opentelemetry.io/docs/languages/js/getting-started/nodejs/?utm_source=chatgpt.com "Node.js"
[26]: https://apisecurity.io/encyclopedia/content/owasp-api-security-top-10-cheat-sheet-a4.pdf?utm_source=chatgpt.com "OWASP API Security Top 10"
[27]: https://docs.opensearch.org/latest/query-dsl/full-text/multi-match/?utm_source=chatgpt.com "Multi-match queries"
