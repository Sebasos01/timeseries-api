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
