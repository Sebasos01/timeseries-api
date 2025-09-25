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

The Phase 1 Docker Compose stack mirrors the Architecture Guide’s split gateway/service topology and focuses on the core gateway/service components.

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
- http://localhost:8080/v3/api-docs
- http://localhost:8080/swagger-ui.html

Set `AUTH_ENABLED=true` and point `OAUTH_JWKS_URI` at a JWKS endpoint to require JWTs; leave the toggle off for local hacking.
## Security posture (Phase 9)

- **HTTPS only**: deploy the gateway behind CloudFront or an ALB with TLS termination. Helmet already sends HSTS headers so browsers stay on HTTPS.
- **Authentication & rate limiting**: the gateway enforces a per-client `x-api-key` (stored in `.env` locally, Secrets Manager/SSM in AWS) and throttles requests with `express-rate-limit`.
- **Least privilege DB access**: connect with the `ts_api_ro` role (SELECT only). Provision the user separately and avoid using superuser credentials in the app configuration.
- **Secrets management**: never hard-code credentials; load them from environment variables locally and from AWS Secrets Manager/Parameter Store in deployed environments.
- **Edge protections**: front the gateway with CloudFront + AWS WAF (with Shield Standard) to enforce HTTPS, HSTS, and absorb L3/L4 attacks.
- **Validated inputs**: Spring controllers are annotated with `@Validated`, enforce a `seriesId` regex, and coerce query parameters into enums so invalid values fail fast with 400 responses.
- **Logging & error handling**: JSON logs (pino-http) include request IDs; Problem Detail responses keep error payloads consistent.
- Review OWASP cheat sheets for [Authentication](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html), [Input Validation](https://cheatsheetseries.owasp.org/cheatsheets/Input_Validation_Cheat_Sheet.html), [REST Security](https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html), and [Logging](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html) before production hardening.
- **Reindex**: POST http://localhost:8081/admin/reindex (gateway) triggers the async bulk reindex so newly added series appear in search.


- **Docs**: Gateway hosts docs at http://localhost:8081/docs (UI) and exposes the YAML at /docs/openapi.yaml. Use npm run sdk:all to regenerate SDKs.
