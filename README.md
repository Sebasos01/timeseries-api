# Time Series Data API

This mono-repo hosts the edge gateway and core service for a high-performance time series API, mirroring the CEIC-inspired architecture where a Node.js façade protects and fronts a Java 21 service.

## Repository layout

```
timeseries-api/
├─ gateway/              # Node.js façade (TypeScript)
├─ service/              # Java Spring Boot service scaffold
├─ ops/
│  ├─ docker/            # Docker and compose assets
│  └─ k6/                # Load test stubs
├─ docs/
│  ├─ 01-conceptual-architecture-and-api-design.txt
│  └─ 02-phased-implementation-playbook.md
├─ .github/              # CI and repo policies
├─ .editorconfig         # Shared formatting rules
├─ .gitattributes        # Line ending normalization
├─ .gitignore            # Build, env, dependency exclusions
├─ .env.example          # Sample runtime configuration
├─ LICENSE               # MIT license
└─ README.md
```

## Getting started

1. Install prerequisites: Node.js 22+, npm 10+, Java 21, and Gradle (wrapper included).
2. Copy `.env.example` to `.env` and populate secrets locally; keep `.env` out of version control.
3. Gateway placeholder:
   ```bash
   cd gateway
   npm install
   npm run build
   npm test
   ```
4. Service placeholder:
   ```bash
   cd service
   ./gradlew build
   ```

The repository follows Conventional Commits and keeps security controls (authn/z, rate limits, secrets via env) front and center even in placeholders.

## Local dev stack

The Phase 1 Docker Compose stack mirrors the Architecture Guide’s split gateway/service topology and seeds observability plumbing (Prometheus, Grafana, OpenTelemetry).

```bash
cd ops/docker
cp ../../.env.example ../../.env  # if not already present
# Core stack
docker compose up --build
# Optional services
docker compose --profile redis up --build
docker compose --profile jaeger up --build
```

Key endpoints once the stack is healthy:

- Gateway health: http://localhost:8081/health → `{ "ok": true }`
- Service actuator health: http://localhost:8080/actuator/health → `{"status":"UP"}`
- OpenSearch banner: http://localhost:9200
- Prometheus: http://localhost:9090 (scrapes the service at `/actuator/prometheus`)
- Grafana: http://localhost:3001 (default admin/admin, update on first login)
- OTEL collector health: http://localhost:13133/healthz
- (Profile) Redis: http://localhost:6379
- (Profile) Jaeger UI: http://localhost:16686

Use `make up`, `make down`, `make logs`, or `make ps` from the repo root as shortcuts for the same compose operations.

## Spring Boot service

Run the service locally when iterating without Docker:

```
cd service
./gradlew bootRun
```

Key endpoints while the application is running:

- http://localhost:8080/ (service banner)
- http://localhost:8080/v1/ping
- http://localhost:8080/actuator/health
- http://localhost:8080/actuator/prometheus
- http://localhost:8080/v3/api-docs
- http://localhost:8080/swagger-ui.html

Set `AUTH_ENABLED=true` and point `OAUTH_JWKS_URI` at a JWKS endpoint to require JWTs; leave the toggle off for local hacking.
