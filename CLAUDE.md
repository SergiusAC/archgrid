# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

ArchGrid is an Architecture Knowledge Platform, not a diagramming tool. The governing
principle, from which most design decisions follow:

> The architecture graph is the source of truth. Diagrams are views of it, documentation is
> a projection of it, AI proposes changes to it and never writes to it directly.

The project is at **phase 0 (foundation) complete** on a nine-phase plan. There is no
metamodel and no editor yet. Before proposing work, read `docs/03-roadmap.md` to see which
phase the request belongs to — much of what looks "missing" is deliberately deferred.

Decisions are recorded in `docs/adr/`. `docs/01-tech-stack-review.md` explains what was
rejected from the original idea and why. Do not re-introduce a rejected component without
reading its ADR first.

## Commands

All Gradle commands run from `server/`; all npm commands from `web/`.

```bash
# Server
./gradlew build                                  # compile, all tests, bootJar
./gradlew test                                   # tests only
./gradlew :archgrid-domain:test --tests "dev.archgrid.domain.shared.SlugTest"
./gradlew :archgrid-app:test --tests "*ArchitectureRulesTest.domain_is_free_of_frameworks"
./gradlew bootRun                                # needs the compose stack up

# Web
npm run lint      # oxlint (not eslint)
npm run build     # tsc -b && vite build — this is also the type-check
npm run dev

# Local stack: PostgreSQL on 5433, Keycloak on 8081
docker compose -f deploy/docker-compose.yml up -d
docker compose -f deploy/docker-compose.yml down
```

`--tests` filters match the **Java method name**, not the `@DisplayName` text.

Dev users are `architect`/`architect` and `reader`/`reader` in the `archgrid` Keycloak
realm. The workspace API is read-only until phase 2, so seed the first row by hand — see
README.md.

## Architecture

### Module boundaries are enforced, not documented

`server/` has only two Gradle modules, and that is deliberate:

- **`archgrid-domain`** depends on nothing but the JDK and JSpecify. It exists as a separate
  module for exactly one reason: to make "no Spring, no JPA, no Jackson, no `java.sql` in the
  domain" a compile-time guarantee. Adding a dependency here is a design error.
- **`archgrid-app`** holds everything else. Feature slices (`metamodel`, `model`, `views`,
  `history`, `search`, `imports`, `ai`, `admin`, `platform`) are **packages**, not modules,
  with boundaries enforced by `ArchitectureRulesTest`. Nine Gradle modules would be a tax
  without benefit for a single developer.

`ArchitectureRulesTest` fails the build on: framework leakage into the domain, domain
depending on adapters, a `*Controller` reaching a `*Repository`, field injection, and cycles
between feature slices. Treat a failure there as a design signal, not an obstacle to route
around.

### The three-layer split (ADR-0003)

Not two layers, three. This is the most load-bearing decision in the product:

| Layer | Versioned | Conflict handling |
|---|---|---|
| Semantic model — elements, relationships, types | Yes, by commit | Transaction + metamodel validation |
| View definition — viewpoint, membership, filters | Yes, by commit | Transaction |
| Layout — coordinates, waypoints, styles | No; mutable, optimistic lock | Last-write-wins |

Separating view definition from layout is what keeps the change log meaningful: dragging a
box must never produce a commit. It is also what makes CRDT viable later for layout only,
without risking model correctness.

Consequence to respect in any UI work: **an element exists independently of any diagram.**
"Remove from this view" and "delete from the model" must stay distinct operations, the second
showing where else the element is used.

### Changes to a model are commands only (ADR-0004)

From phase 2, the single mutation path is:
authz → optimistic check on `baseCommitId` → apply to an in-memory snapshot → validate
against the metamodel → transactional commit → invalidate indexes → broadcast.

No direct UPDATEs from controllers. Validation errors must *explain* — which rule was
violated and what would have been permitted — because teaching the notation is half the
product's value. `ApiExceptionHandler` establishes the RFC 9457 shape for that.

`commit_id` already exists in the schema even though history UI arrives in phase 5.
Retrofitting versioning later would mean rewriting every query.

### Persistence

PostgreSQL is the only stateful component, and there is no ORM — explicit SQL over
`JdbcClient`. Graph traversal is recursive CTEs plus an in-memory index rebuilt per model
version. Neo4j was rejected (ADR-0001) on licensing, scale and on-premise operational cost;
OpenSearch was rejected in favour of `pgvector` and Postgres FTS.

Every table carries `workspace_id`. Every addressable object carries both a UUID (stable
across renames, what the system references) and a slug (what humans, URLs and the future
Architecture-as-Code format reference). `Slug`'s regex is duplicated as a DB check
constraint on purpose — the database is the last defence against a bad import.

### Identity

Stateless OAuth2 resource server. ArchGrid stores no passwords and issues no tokens. Role
claim paths are configuration (`archgrid.security.role-claim-paths`) so a customer can point
it at whatever their IdP emits. Health probes are deliberately reachable without a token:
otherwise an IdP outage makes an orchestrator kill healthy pods.

## The ArchiMate metamodel rule (phase 1) — read before touching `metamodel/`

The metamodel is **data**, not code: element types, relationship types, structural-strength
ordering and the ~60×60 permitted-relationship matrix live in a YAML file loaded at startup.

> The matrix and the strength ordering must be transcribed from the official ArchiMate 3.2
> specification, Appendix B. **Not from memory, not generated by an LLM, not copied from a
> third-party repository without line-by-line verification.**

This is the one place where a plausible-looking guess is actively harmful: an error makes the
product give an architect a false guarantee about their model's correctness. If asked to fill
in the matrix without a source, say so and ask for the specification rather than producing
something that looks right. See ADR-0002.

**Source status — read `metamodel/VERIFICATION.md` before touching the metamodel.** The base
is Archi's `relationships.xml` / `viewpoints.xml` (MIT, declares `version="3.2"`). Vocabulary
— 11 relationship types and 60 element types — is verified against The Open Group exchange
XSD and matches exactly. The **matrix itself, the strength ordering and the viewpoint
palettes are still unverified**, because the XSD does not describe them; only Appendix B can.
Until that is closed, the product may not claim metamodel conformance.

Two facts established from the data that will otherwise cost you time:

- The matrix substantially includes **derived** relationships (97 % closure under the
  structural weakest-link rule on core concepts). It answers "may this relationship exist
  between these types", never "is this instance explicit or derived" — that is computed by
  our own derivation engine from the model graph.
- `viewpoints.xml` palettes contain macro placeholders (`$ApplicationElements$`,
  `$BusinessElements$`, and five more) that the loader must expand. An implausibly small
  palette is a parsing bug; an empty one (`layered`) means all concepts are allowed.

Wording is constrained: "ArchiMate 3.2 compatible" is permitted, "ArchiMate-certified" or
"conforming implementation" is not, anywhere in UI, docs or README.

## Environment and version discipline

Versions in this repo were verified against `repo1.maven.org/.../maven-metadata.xml`, not
recalled. **`search.maven.org` returns a stale index** — it reported Spring Boot 3.5.3 as
latest when 4.1.0 was GA. `gradle/libs.versions.toml` pins only three libraries; anything the
Spring Boot BOM manages is intentionally left unpinned.

Non-obvious breakages already hit and fixed — do not re-introduce:

- **Spring Boot 4 split auto-configuration into per-technology modules.**
  `org.flywaydb:flyway-core` provides the library but runs no migrations; the starter
  `spring-boot-starter-flyway` is required. Likewise MockMvc moved to
  `spring-boot-starter-webmvc-test`, package `org.springframework.boot.webmvc.test.autoconfigure`.
- **Testcontainers 2.x** renamed every module to `testcontainers-*` and moved classes
  (`org.testcontainers.postgresql.PostgreSQLContainer`, non-generic).
- **PostgreSQL 18+ images** must mount `/var/lib/postgresql`, not `/var/lib/postgresql/data`.
- The dev database is on **5433**, not 5432, because 5432 is usually already taken.
- Java compiles with `-Werror` and a curated `-Xlint` set; TypeScript runs with
  `erasableSyntaxOnly`, so constructor parameter properties are rejected.
- Integration tests are skipped when Docker is absent (`dev.archgrid.support.Docker`) so the
  build stays usable without it. A "skipped" IT locally is not a passing IT.

On Windows PowerShell, pass multi-line git commit messages via `git commit -F <file>`; native
argument parsing mangles quotes inside here-strings.

## Deliberately absent — do not add without revisiting the ADR

Neo4j, OpenSearch, Kafka, Redis, a Node runtime, microservice decomposition, an ORM, Yjs/CRDT
for the semantic model, and per-tenant custom metamodels. Each was considered and rejected;
the reasoning is in `docs/01-tech-stack-review.md` and `docs/adr/`. Any new stateful component
is paid for at every customer installation.
