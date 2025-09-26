# OpenAPI & Developer Experience

## Viewing the API spec

- Gateway Swagger UI: http://localhost:8081/docs/ui
- Raw OpenAPI YAML (via gateway): http://localhost:8081/docs/openapi.yaml
- Service Swagger UI (direct, when the Spring app is running outside Docker): http://localhost:8080/swagger-ui.html

## Regenerating the spec & SDKs

```bash
npm install  # installs openapi-generator CLI if not already present
npm run spec:pull
npm run sdk:all
```

Generated SDKs are placed in `sdks/typescript` and `sdks/python`. Regenerate after API changes and publish to your package registries as needed.

## Rate-limit headers

All responses include IETF draft headers (`RateLimit`, `RateLimit-Policy`) alongside conventional `X-RateLimit-*` fields; 429 responses may include `Retry-After`.

## Problem Details

Errors follow RFC 9457 (`application/problem+json`). Each `type` points to markdown pages served by the gateway under `/docs/problems/{slug}`.
