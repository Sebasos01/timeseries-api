# Time Series Data API

This repository hosts the public-facing gateway and core service that make up the time series API. The gateway is an Express/TypeScript app that terminates HTTPS, enforces rate limits, validates OAuth2 access tokens, and proxies requests to the Spring Boot service. The service is a Java 21 application that exposes the domain APIs, persists data in PostgreSQL, and publishes OpenAPI documentation.

## Repository structure

```
timeseries-api/
|- gateway/                 Express gateway (TypeScript)
|- service/                 Spring Boot service (Java 21)
|- ops/
|  |- docker/               Docker Compose stack and helper scripts
|- docs/                    Architecture and design notes
|- postman/                 Postman collection for manual testing
|- certs/                   Self-signed development certificates (ignored in prod)
|- .env.example             Sample environment configuration
|- Makefile                 Convenience targets for Docker workflows
|- README.md
```

## Prerequisites

- Node.js 22 or newer and npm 10 or newer
- Java 21 (JDK) - the Gradle wrapper is provided
- Docker and Docker Compose (optional, for the local stack)
- OpenSSL (optional, to regenerate the development certificates under `certs/`)

## First-time setup

1. Copy the environment template and adjust values as needed:
   ```bash
   cp .env.example .env
   ```
   The same file is consumed by the gateway, the service, and the Docker Compose stack. Keep secrets out of version control.
2. Install gateway dependencies:
   ```bash
   cd gateway
   npm install
   ```
3. (Optional) Warm the service build cache:
   ```bash
   cd ../service
   ./gradlew build
   ```

## Running locally without Docker

### Gateway (Express + TypeScript)

```bash
cd gateway
npm run dev        # starts the HTTPS gateway using tsx
npm run build      # type-check and emit JS to dist/
```

The gateway listens on HTTPS :${HTTPS_PORT} (default 8443). An auxiliary HTTP listener can redirect or serve traffic depending on `HTTP_MODE`. Update `.env` to point `JAVA_API_URL` at your service instance.

Key endpoints:
- https://localhost:8443/health - gateway health check
- https://localhost:8443/docs - API reference UI served by the gateway
- https://localhost:8443/docs/openapi.yaml - raw OpenAPI document

### Service (Spring Boot)

```bash
cd service
./gradlew bootRun    # runs the service on port 8080
./gradlew build      # compiles and executes unit/integration tests
```

Important endpoints while running locally:
- http://localhost:8080/actuator/health
- http://localhost:8080/v3/api-docs
- http://localhost:8080/swagger-ui.html

Enable authentication by setting `AUTH_ENABLED=true` and providing `OAUTH_JWKS_URI`, `OAUTH_AUDIENCE`, and `OAUTH_ISSUER`. The gateway will enforce JWT validation and forward the bearer token to the service.

## Docker Compose development stack

A convenience stack lives under `ops/docker` and brings up PostgreSQL, the service, the gateway, and optional observability tooling.

```bash
cd ops/docker
cp ../../.env.example ../../.env    # if you have not created .env yet
docker compose up --build            # base stack (gateway + service + database)
```

Optional profiles:
- docker compose --profile redis up --build
- docker compose --profile jaeger up --build

Shortcuts are available from the repo root via `make up`, `make down`, `make logs`, and `make ps`.

## Regenerating SDKs and docs

The root package.json exposes scripts to sync the OpenAPI document and regenerate client SDKs using OpenAPI Generator:

```bash
npm run spec:pull   # refresh docs/openapi.yaml from the running gateway
npm run sdk:ts      # generate the TypeScript SDK under sdks/typescript
npm run sdk:py      # generate the Python SDK under sdks/python
```

## Security posture

- HTTPS everywhere: the gateway terminates TLS using the certificates in `certs/` for local development. Helmet configures HSTS (max-age=63072000, includeSubDomains, preload).
- Hardened cookies: all cookies set through `res.cookie` are forced to Secure, HttpOnly, SameSite=strict, and path='/'. Requests that override SameSite to none are still upgraded to Secure.
- Strict headers: the gateway enables Helmet protections for frame denial, referrer policy (strict-origin-when-cross-origin), CORP/COOP/COEP, and sends a restrictive Permissions-Policy (geolocation=(), camera=(), microphone=()).
- Spring security headers: the service disables the stock header defaults and issues the same protections (HSTS, frame denial, referrer policy, CORP/COOP/COEP, custom permissions policy). Tomcat is customized to strip Server and disable X-Powered-By.
- Session configuration: Spring session cookies are marked http-only, secure, and same-site: strict. The service runs stateless when JWT auth is enabled.
- Rate limiting: the gateway throttles clients (express-rate-limit) and echoes RateLimit-* headers to simplify client back-off.
- Admin operations: endpoints such as /admin/reindex and /v1/series/batch require a token that carries the ADMIN_SCOPE (defaults to admin:reindex). You can also enforce role-based access by setting ADMIN_ROLE and ADMIN_ROLE_CLAIM.

Review the OWASP Session Management and HTTP Headers cheat sheets before promoting changes to production.

## Troubleshooting

- Gradle test workers fail to start: run with `./gradlew build --no-daemon` to avoid daemon reuse. Ensure Java 21 is on your PATH.
- Certificates: regenerate local certificates with `make certs` (or `openssl` commands in certs/README.md if present) when they expire.
- CORS: by default the gateway rejects cross-origin requests. Update the CORS configuration in `gateway/src/app.ts` if you need to allow a browser client.

## Contributing

Follow Conventional Commits for branch and commit messages. Run `npm run build` in `gateway/` and `./gradlew build` in `service/` before opening a PR. Security-related changes should note any new headers, cookie attributes, or environment variables introduced.