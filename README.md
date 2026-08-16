# ArchGrid

Architecture Knowledge Platform. The architecture graph is the source of truth; diagrams
are views of it, documentation is a projection of it, and AI reads and proposes changes to
it — never writes to it directly.

**Status: phase 0 (foundation) complete.** The skeleton builds, tests and runs end to end.
There is no editor and no metamodel yet — those are phases 1–3. See
[docs/03-roadmap.md](docs/03-roadmap.md).

## Documentation

| Document | What it covers |
|---|---|
| [docs/01-tech-stack-review.md](docs/01-tech-stack-review.md) | Which parts of the original idea survived scrutiny, and why the rest did not |
| [docs/02-architecture.md](docs/02-architecture.md) | Layers, data model, change pipeline, on-premise constraints |
| [docs/03-roadmap.md](docs/03-roadmap.md) | Nine phases, estimates, milestones, risks |
| [docs/adr/](docs/adr/) | Six accepted architecture decisions |

## Layout

```
archgrid/
├── server/              Gradle multi-module build (Java 21, Spring Boot 4.1)
│   ├── archgrid-domain/ Pure Java: no Spring, no JPA, no Jackson. Enforced by ArchUnit.
│   └── archgrid-app/    Adapters: REST, security, persistence, feature slices
├── web/                 React 19 + TypeScript + Vite
├── metamodel/           ArchiMate 3.2 metamodel as data — filled in phase 1
├── deploy/              Local stack; the Helm chart arrives in phase 8
└── docs/                Architecture, roadmap, ADRs
```

## Prerequisites

- JDK 21
- Node 24
- Docker (for the local stack and for Testcontainers-backed tests)

## Running it

```bash
# 1. Infrastructure: PostgreSQL on 5433, Keycloak on 8081
docker compose -f deploy/docker-compose.yml up -d

# 2. API on 8080 — Flyway applies the schema at startup
cd server && ./gradlew bootRun

# 3. Web client on 5173
cd web && npm install && npm run dev
```

Sign in as `architect` / `architect` (or `reader` / `reader`).

The database is published on **5433**, not the usual 5432, because a developer machine
very often already has something on the default port. Override with `ARCHGRID_DB_PORT`;
the default in `application.yaml` matches.

### Seeding a workspace

The workspace API is read-only until phase 2, so the first row goes in by hand:

```bash
docker exec archgrid-postgres psql -U archgrid -d archgrid \
  -c "insert into workspace (id, \"key\", name) values (gen_random_uuid(), 'default', 'Default workspace')"
```

### Calling the API directly

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/realms/archgrid/protocol/openid-connect/token \
  -d grant_type=password -d client_id=archgrid-web \
  -d username=architect -d password=architect | jq -r .access_token)

curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/workspaces
```

## Building and testing

```bash
cd server && ./gradlew build     # unit, integration and architecture tests
cd web && npm run lint && npm run build
```

Integration tests use Testcontainers and are **skipped** when Docker is unavailable, so the
build stays usable without it. CI has Docker, so nothing is silently lost there.

## Conventions worth knowing before contributing

- **The domain module depends on nothing but the JDK.** Adding Spring, Jackson, JPA or
  `java.sql` to `archgrid-domain` is a design error. `ArchitectureRulesTest` fails the build
  if you do.
- **Gradle modules are few on purpose.** Feature boundaries (metamodel, model, views,
  history, search, import, ai, admin) are packages inside `archgrid-app`, enforced by
  ArchUnit rather than by the build.
- **Every table carries `workspace_id`, every addressable object carries a UUID and a slug.**
  Established in migration `V1`; see [ADR-0003](docs/adr/0003-model-view-layout-separation.md).
- **From phase 2, the only way to change a model is a validated command.** Controllers may
  not reach repositories — also an ArchUnit rule.
- **Versions are verified, not remembered.** Dependency versions in
  `gradle/libs.versions.toml` were checked against Maven Central; everything the Spring Boot
  BOM manages is intentionally left unpinned.
