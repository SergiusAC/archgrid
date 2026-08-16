# Metamodel

`archimate-3.2.yaml` is the canonical ArchiMate metamodel ArchGrid loads at startup: 11
relationship types, 62 concepts (60 element types plus two matrix endpoints), the 62 × 62
permitted-relationship matrix, and 25 viewpoints with expanded palettes.

**It is generated. Do not edit it by hand.**

```
metamodel/
├── archimate-3.2.yaml     generated canonical metamodel (~4300 lines)
├── VERIFICATION.md        what was checked against a first-party source, and what was not
└── upstream/archi/        vendored Archi sources, pinned to a commit — see PROVENANCE.md
```

## Regenerating

```bash
cd server && ./gradlew generateMetamodel
```

Then read the diff before committing it. That is the entire point of this arrangement: an
unexplained change in the matrix is a defect, not an update. `MetamodelGenerationTest` fails
the build if the committed file is not exactly what the generator produces, so a hand edit or
a half-applied upstream change cannot survive.

## What is derived from what

| Output | Derived from |
|---|---|
| Relationship types, keys | `relationships-keys.xml` |
| Relationship category | `archimate.ecore` — the `eSuperTypes` of each relationship class |
| Element types | source concepts of `relationships.xml` |
| Layer, aspect | `archimate.ecore` — `ApplicationComponent` is an `ApplicationElement` and an `ActiveStructureElement` |
| Permitted matrix | `relationships.xml`, letters sorted for stable diffs |
| Viewpoints, palettes | `viewpoints.xml`, with `$ApplicationElements$`-style macros expanded against the same type hierarchy |
| Structural strength ordering | **nothing upstream** — see below |

## The one value that is not derived

Archi encodes which relationship types are structural but not their relative strength, and
the weakest-link derivation rule needs an order. The generator carries the conventional
ordering — Composition, Aggregation, Assignment, Realization — and marks it **unverified**,
in the code, in the generated file and in `VERIFICATION.md`. It affects derived relationships
(phase 4) only, never plain validation.

## Two traps this format exists to avoid

- **Viewpoint macros.** Upstream palettes are not plain concept lists. Parsed naively,
  Application Cooperation is a two-item palette and Technology is a two-item palette. The
  generator expands them; the loader would have no way to know they were wrong.
- **YAML booleans.** `no` is a legitimate matrix value — Influence plus Association — and
  YAML 1.1 parses it unquoted as the boolean `false`. Every matrix value and every
  relationship key in the generated file is quoted for this reason. This was found by a test,
  after it had already silently corrupted one cell.

## Verification status

Vocabulary is verified against The Open Group exchange-format XSD. The matrix, the strength
ordering and the palettes are **accepted on the authority of Archi** by an explicit decision
recorded in [ADR-0002](../docs/adr/0002-metamodel-as-data.md); they have not been checked
against the specification's Appendix B. Until that is closed the product may not claim
metamodel conformance. Details in [VERIFICATION.md](VERIFICATION.md).
