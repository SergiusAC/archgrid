# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

ArchGrid is an Architecture Knowledge Platform, not a diagramming tool. The governing
principle, from which most design decisions follow:

> The architecture graph is the source of truth. Diagrams are views of it, documentation is
> a projection of it, AI proposes changes to it and never writes to it directly.

The project is at **phase 4 (views, derivation, projections) in progress** on a nine-phase plan:
foundation, the ArchiMate metamodel, the command pipeline, views with folders and an editor of
roughly Archi's capability exist; C4 projections, view filters, history UI, import, AI and
enterprise readiness do not. Before proposing work, read `docs/03-roadmap.md`
to see which phase the request belongs to — much of what looks "missing" is deliberately
deferred. `docs/04-domain-model.md` explains the entities and, in particular, the four levels
of "type-ness" that are easy to conflate.

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
./gradlew generateMetamodel                      # rewrite metamodel/archimate-3.2.yaml, then read the diff

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
| Layout — coordinates, sizes, nesting, stacking order, hidden lines, line router | No; mutable, optimistic lock | Last-write-wins |

Separating view definition from layout is what keeps the change log meaningful: dragging a
box must never produce a commit. It is also what makes CRDT viable later for layout only,
without risking model correctness.

Consequence to respect in any UI work: **an element exists independently of any diagram.**
"Remove from this view" and "delete from the model" must stay distinct operations, the second
showing where else the element is used.

**Nesting is presentation, not structure (ADR-0011).** Dropping one element into another changes
where it is drawn on *that* view and nothing else — it is layout, produces no commit, and the same
element may stand alone on the next view. What the nesting *means* is a separate question, so the
editor offers to create a structural relationship; the relationship then lives in the model like
any other and its line is merely hidden where the nesting already says it.

`element.parent` still exists in the domain, in `move-element` and in the schema, and
`RelationshipDerivation` still reads it as composition — so derivation leans on a field the UI no
longer fills. That is the unfinished half of ADR-0011, not a design.

### Changes to a model are commands only (ADR-0004)

From phase 2, the single mutation path is:
authz → optimistic check on `baseCommitId` → apply to an in-memory snapshot → validate
against the metamodel → transactional commit → invalidate indexes → broadcast.

No direct UPDATEs from controllers. Validation errors must *explain* — which rule was
violated and what would have been permitted — because teaching the notation is half the
product's value. `ApiExceptionHandler` establishes the RFC 9457 shape for that.

`commit_id` already exists in the schema even though history UI arrives in phase 5.
Retrofitting versioning later would mean rewriting every query.

### The workspace is the repository (ADR-0008)

A workspace holds **exactly one connected graph**, enforced by `unique (workspace_id)` on
`model` since V4. Elements are shared by every view of it; there is no model segment in the
API and nothing for a user to create or name below the workspace. The many named things an
architect makes are views, arriving in phase 4.

This corrects an earlier design that allowed several models per workspace. The damage was not
the missing reuse — it was that "where is this used", impact analysis and the phase 6 drift
report would have answered from inside one model and been silently wrong about the rest of the
landscape. Do not reintroduce a model selector.

### Views and folders (ADR-0009)

A **view** is what an architect creates and names — a context diagram of one system, the
modules of another. It is a viewpoint plus a selection of repository elements, and the same
element appears on any number of views. Its relationships are **computed**, not stored: those
with both endpoints on the view.

Four layers now, and what is versioned matters:

| Layer | Versioned |
|---|---|
| Elements, relationships | yes, by commit |
| View name, viewpoint, membership | yes, by commit |
| Layout: coordinates, sizes, nesting, stacking order, hidden lines, router | **no** — own endpoint, `PUT .../views/{key}/layout` |
| Folders | **no** — plain REST |

**A folder means nothing to the metamodel.** It does not affect validation, derivation or C4
depth; a Composition relationship does, and drawing one element inside another is a third thing
again — presentation of that relationship on one view (ADR-0011). Confusing them gives a product
where filing something elsewhere changes what a diagram shows.

Filing is read alongside the graph rather than from it, which is easy to forget: a listing that
returns elements without their folder makes the tree draw everything at the root, and moving
something then appears to do nothing at all.
Two separate trees, `elements` and `views`. Deleting a folder moves its contents up rather
than deleting them.

### Derived relationships (phase 4)

Computed by `RelationshipDerivation`, **never stored**: storing them would make the history
unreadable and go stale the moment anything on the path changed. A view gets them as a separate
list, each carrying the path it came through so a diagram can answer "why is this line here".

Two rules, along a path whose intermediate elements are all hidden: a structural chain derives
the weakest link; a structural chain ending in one dependency or dynamic relationship derives
that. Nothing derives past a dependency.

**Containment counts as composition.** `element.parent` is ArchiMate composition written as a
field, and derivation that ignored it would miss the case context views exist for.

### Persistence

PostgreSQL is the only stateful component, and there is no ORM — explicit SQL over
`JdbcClient`. Neo4j was rejected (ADR-0001) on licensing, scale and on-premise operational
cost; OpenSearch was rejected in favour of `pgvector` and Postgres FTS.

**Versioning uses validity ranges, not materialised current-state tables** — the sketch in
`docs/02-architecture.md` §4.2 was superseded. Every version row carries
`[from_seq, to_seq)` over a per-model commit sequence; `to_seq is null` means current. HEAD
and any historical state are each one indexed scan, with no second table to keep consistent.
The sequence is allocated under a row lock on `model`, which also serialises concurrent
commits — the optimistic check on `baseCommitId` only means something if two writers cannot
interleave between checking and writing. This works because history is linear; branching
would need the ancestry walk the original sketch implied.

Commands reference elements by **slug, not UUID**. Slugs are stable across renames, so they
are as safe as identifiers, and they keep the command log readable, the DSL diffable and
AI-proposed changes producible. A command stream full of UUIDs is correct and useless.

Every table carries `workspace_id`. Every addressable object carries both a UUID (stable
across renames, what the system references) and a slug (what humans, URLs and the future
Architecture-as-Code format reference). `Slug`'s regex is duplicated as a DB check
constraint on purpose — the database is the last defence against a bad import.

### Identity

Stateless OAuth2 resource server. ArchGrid stores no passwords and issues no tokens. Role
claim paths are configuration (`archgrid.security.role-claim-paths`) so a customer can point
it at whatever their IdP emits. Health probes are deliberately reachable without a token:
otherwise an IdP outage makes an orchestrator kill healthy pods.

**Role names are upper-cased when mapped to authorities.** Spring's `hasRole('OWNER')`
compares authority strings exactly, while an IdP emits whatever casing the customer typed —
Keycloak realm roles are conventionally lower case. Without normalising, every mutation
returns 403 at an installation whose roles are not already upper case. This shipped broken
once because every test injected authorities directly and none exercised a real token; when
testing authorisation, cover the converter too.

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

**Decided and done.** The source is Archi's vendored data (MIT, pinned commit, in
`metamodel/upstream/archi/`), accepted as valid by an explicit decision in ADR-0002.
`metamodel/archimate-3.2.yaml` is **generated** from it — never edit it by hand; run
`./gradlew generateMetamodel` and read the diff. `MetamodelGenerationTest` fails the build if
the committed file is not exactly what the generator produces.

Verification status, which still constrains what the product may claim: the vocabulary is
verified against The Open Group exchange XSD and matches exactly; the **matrix, the
structural strength ordering and the viewpoint palettes are unverified** against Appendix B.
Until that closes, no conformance claims. "ArchiMate 3.2 compatible" is permitted;
"ArchiMate-certified" or "conforming implementation" is not, anywhere in UI, docs or README.

Facts established from the data that will otherwise cost you time:

- The matrix substantially includes **derived** relationships (97 % closure under the
  structural weakest-link rule on core concepts). It answers "may this relationship exist
  between these types", never "is this instance explicit or derived" — that is computed by
  our own derivation engine from the model graph.
- Viewpoint palettes upstream contain macros (`$ApplicationElements$` and six more). The
  generator expands them. An implausibly small palette is a parsing bug; an empty one
  (`layered`) legitimately means all concepts are allowed.
- **Quote YAML values that are letter sets.** `no` is a real matrix value (Influence +
  Association) and YAML 1.1 parses it unquoted as boolean `false`. This already corrupted a
  cell once before a test caught it.
- The matrix has exactly three empty cells, all between the pseudo-concepts `Junction` and
  `Relationship`. Every pair of real element types permits something. A fourth would be a
  change to the language, and a test pins it.
- Strength ordering is the **one value not derived from upstream** — Archi does not encode
  it. It is the conventional order, marked unverified in code and in the generated file.

### Profiles — ArchiMate's own extension mechanism (ADR-0002)

"Specialization" means two different things in ArchiMate and both are supported, differently:

- The **Specialization relationship** is one of the 11 relationship types, no special handling.
- **Specialization of concepts** — Archi calls it a Profile, the exchange format a Stereotype —
  lets a model define "Microservice" as a kind of Application Component. This is how a
  customer extends the language without the product abandoning conformance, which is why
  arbitrary per-tenant metamodels are still refused.

The invariant that makes profiles safe: **a profiled element is validated exactly as its base
concept.** A profile adds vocabulary and attributes, never changes what is permitted.
Resolution happens in one place, `ProfileCatalog.resolve`; anything that resolves a type name
elsewhere is a bug waiting to break that guarantee.

Profiles belong to a model, not the metamodel — they are customer content, stored in the
`profile` table, never in the generated `archimate-3.2.yaml`. Attribute types are exactly the
exchange format's six so export loses nothing; mandatory-attribute rules are deliberately not
modelled here (they are model-quality governance and would not survive a round trip).
Specialisability is read from the matrix rather than hard-coded: 61 of 62 concepts qualify,
`Relationship` being the exception.

### C4 (ADR-0007)

`metamodel/c4-mapping.yaml` is **hand-authored** and lives separately from the generated
metamodel on purpose: one is derived from upstream and checked for drift, the other is an
editorial decision. The loader validates every concept it names against the metamodel.

Two things here are easy to get wrong and expensive when wrong:

- **C4 levels are never stored on an element.** Whether something is a Software System or a
  Container depends on where the boundary is drawn — the same service is both, seen from
  different systems. Level is depth relative to a view's focus. Do not add a "C4 type" field.
- **C4 and ArchiMate point relationships in opposite directions.** C4 draws consumer →
  provider ("uses"); ArchiMate Serving is provider → consumer ("serves"). The `reverse` flag
  on `C4RelationshipIntent` handles it. Getting this wrong yields a plausible-looking diagram
  whose impact analysis is exactly backwards.

`C4Shape.OMITTED` is the default and the correct answer for 52 of 62 concepts — C4 has no
behaviour, interfaces, motivation, strategy or implementation elements. The count of
representable concepts is pinned by a test; changing it is a design change for ADR-0007.

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
- **Spring Boot 4 uses Jackson 3** (`tools.jackson.databind.ObjectMapper`). Jackson 2 is on the
  classpath transitively; importing `com.fasterxml.jackson.databind` gets you an unmanaged
  second mapper.
- Spring Framework 7 renamed 422 per RFC 9110: `HttpStatus.UNPROCESSABLE_CONTENT` and
  `status().isUnprocessableContent()`. The old spellings are deprecated, and `-Werror` fails
  the build on them.
- **Never let a catch-all `@ExceptionHandler(Exception.class)` swallow `AccessDeniedException`,
  `AuthenticationException` or `ErrorResponseException`.** They must reach Spring Security's
  filter and Spring's status resolver, otherwise every 403 and 404 is reported as 500. This
  already happened once; `ApiExceptionHandler` rethrows them and tests pin both statuses.
- **The canvas is JointJS, not React Flow** (ADR-0010). Everything below `Canvas.tsx` is
  imperative and lives outside React's control; the traps are all on that boundary and none of
  them is visible to `tsc` or the linter:
  - **Never pass React's own `div` as the paper's `el`.** JointJS views are Backbone views and
    `remove()` deletes the element it was given — under double-invoked effects the second paper
    then renders into a node no longer in the document, and the canvas is silently blank.
    Construct the paper without `el` and append `view.el`.
  - **SVG percentages resolve against the viewport, not the element.** `cx: '50%'` puts a handle
    in the middle of the canvas, inflates the element's bounding box and drags every line to that
    phantom box. Use JointJS `calc()` expressions.
  - **`attrs.root.class` replaces the class attribute**, so JointJS's own `joint-element` /
    `joint-link` have to be repeated or the library stops finding its views.
  - **`sourceMarker: null` renders `marker-start="undefined"`** — a reference to nothing. The line
    still draws, so the diagram merely looks plain. Pass a marker or the string `'none'`.
  - **Element tools cannot be rebuilt on every render.** A resize emits geometry each frame; if
    that re-render replaces the grip, the gesture dies on the first frame.
  - **Geometry belongs to the canvas while it is mounted.** Pushing positions from props on every
    render races the drag with values one render out of date. External changes — auto-layout, undo
    — arrive through an explicit `geometryVersion`.
  - **A drag on blank canvas selects; panning is the middle button or space-and-drag.** Selecting
    is the frequent act. The band takes what it encloses, never what it merely touches: with
    containers on the diagram, "touch" grabs the container as soon as the band crosses its edge.
  - **Stacking order is JointJS's to compute.** Ask `toFront`/`toBack` and read `z` back rather
    than inventing numbers, and lift children over their parent afterwards — `toBack` on a nested
    element files it behind its own container, where it looks deleted.
- **Layout is saved when the user asks, not automatically.** Changes live as a draft in
  `localStorage` and reach the server on Save or Ctrl+S. The old debounce was the origin of every
  ordering trap here: a save in flight racing a refetch, a write landing after the change it was
  meant to record. Model commands are *not* drafted — a command is validated and committed when
  it is issued (ADR-0004).
- Java compiles with `-Werror` and a curated `-Xlint` set; TypeScript runs with
  `erasableSyntaxOnly`, so constructor parameter properties are rejected.

## Verifying UI changes

Type-checking and unit tests do not catch "the arrows are missing". `playwright` is a
devDependency in `web/` with Chromium under `node_modules` — run node scripts with
`PLAYWRIGHT_BROWSERS_PATH=0`. Bring up the compose stack, `bootRun`, and `npm run dev`, then
drive the app: sign in through Keycloak as `architect`/`architect`, open a model, and **look at
the screenshot**. Three real defects — double-wrapped markers, an empty minimap, and a context
menu falling off the screen edge — were invisible to every other check.
- Integration tests are skipped when Docker is absent (`dev.archgrid.support.Docker`) so the
  build stays usable without it. A "skipped" IT locally is not a passing IT.
- **A check that fails against code you can see is correct: confirm the dev server is serving it.**
  `curl -s localhost:5173/src/editor/Editor.tsx | grep …`. Vite's watcher has missed an edit here,
  and the result is a driver run testing the previous version of the file while the source on disk,
  `tsc` and the linter all agree with you. Restart `npm run dev` before doubting the code.

On Windows PowerShell, pass multi-line git commit messages via `git commit -F <file>`; native
argument parsing mangles quotes inside here-strings.

## Deliberately absent — do not add without revisiting the ADR

Neo4j, OpenSearch, Kafka, Redis, a Node runtime, microservice decomposition, an ORM, Yjs/CRDT
for the semantic model, and per-tenant custom metamodels. Each was considered and rejected;
the reasoning is in `docs/01-tech-stack-review.md` and `docs/adr/`. Any new stateful component
is paid for at every customer installation.
