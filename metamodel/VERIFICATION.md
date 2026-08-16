# Metamodel source verification

Performed 2026-08-17, before any of phase 1 was written, to decide what ArchGrid may use
as the machine-readable source of the ArchiMate metamodel. Required by
[ADR-0002](../docs/adr/0002-metamodel-as-data.md).

## Sources compared

| Source | What it is | Version | Licence |
|---|---|---|---|
| The Open Group, [Model Exchange File Format XSD](https://www.opengroup.org/xsd/archimate/3.1/) | First-party schema for the interchange format | **3.1** (no 3.2 directory is published) | The Open Group publication |
| [archimatetool/archi](https://github.com/archimatetool/archi), `com.archimatetool.model/model/` | `relationships.xml`, `relationships-keys.xml`, `viewpoints.xml` | file declares `version="3.2"` | MIT |

## What the XSD can and cannot settle

The exchange-format XSD is authoritative for **vocabulary** — it enumerates the legal
element and relationship type names. It is a document schema and therefore says nothing
about which relationship is permitted between which pair of elements. **The
permitted-relationship matrix cannot be verified against this source.** Only the
specification itself (Appendix B) can settle that.

## Result 1 — relationship types: exact match

`RelationshipTypeEnum` in the XSD holds 11 values. Archi's `relationships-keys.xml` holds
the same 11, with a `Relationship` suffix on each name and a single-letter key:

```
a Access   c Composition  f Flow      g Aggregation  i Assignment  n Influence
o Association  r Realization  s Specialization  t Triggering  v Serving
```

Difference in either direction: **none**.

## Result 2 — element vocabulary: 60 of 62 identical, both differences explained

`ElementTypeEnum` holds 62 values; Archi references 62 concepts. The symmetric difference:

| Only in XSD (3.1) | Only in Archi (3.2) |
|---|---|
| `AndJunction`, `OrJunction` | `Junction`, `Relationship` |

Neither is a disagreement about the language:

- The exchange format serialises junction kind into the **type name**; Archi models a single
  `Junction` concept whose and/or nature is an attribute. Same concept, different encoding.
- `Relationship` is not an element. It appears in Archi's matrix because ArchiMate permits
  relationships that target other relationships, so the matrix needs an endpoint for them.

All 60 real element types agree exactly, across all layers and aspects.

## Result 3 — the matrix substantially includes derived relationships

Structure of `relationships.xml`: 62 × 62 = 3844 ordered pairs, all present, 11569 permitted
relationships in total, averaging 3.01 of the 11 types per cell. Near-total density is
expected — Association is permitted almost everywhere.

Exactly **three** cells permit nothing at all, and all three lie between the two
pseudo-concepts: `Junction → Relationship`, `Relationship → Junction`,
`Relationship → Relationship`. Every pair of real element types permits at least one
relationship. This is pinned by a test rather than merely observed — a fourth empty cell
after an upstream update would mean two element types silently became unconnectable.

To find out whether the table lists only directly permitted relationships or also derivable
ones, the matrix was tested for closure under the structural weakest-link rule (a—R1→b,
b—R2→c implies a—weakest(R1,R2)→c; structural strength Composition > Aggregation >
Assignment > Realization):

| Scope | Chains checked | Derived relationship present |
|---|---|---|
| All 62 concepts | 117 763 | 59.9 % |
| 58 core concepts (excluding `Grouping`, `Location`, `Junction`, `Relationship`) | 26 873 | **97.0 %** |

97 % closure among core elements means the table is **not** a direct-only listing. The
remaining gaps are not scattered: they cluster on chains routed through `WorkPackage` and
the Implementation & Migration layer, where the specification restricts cross-layer
derivation.

**Consequence for ArchGrid.** The matrix answers "may this relationship exist between these
two types at all". It cannot answer "is this particular relationship in the model explicit or
derived" — that distinction has to be computed from the actual model graph by our own
derivation engine, which is what `docs/02-architecture.md` §6 already assumes. Nothing in
the design changes; the earlier open question is closed.

The strength ordering used in the test is the conventional one and is itself pending
confirmation against Appendix B. The closure figure is informative regardless of that
detail, but the ordering must not be shipped unverified.

## Result 4 — spot checks against well-known rules

| Pair | Matrix | Expected |
|---|---|---|
| ApplicationComponent → ApplicationService | `fiortv` | realization present ✓ |
| ApplicationComponent → DataObject | `ao` | access present ✓ |
| BusinessProcess → BusinessProcess | `cfgostv` | triggering and flow present ✓ |
| Goal → Goal | `cgnos` | influence present ✓ |
| Stakeholder → Driver | `no` | influence present ✓ |
| Node → Artifact | `aio` | — |

All consistent.

## Result 5 — viewpoint palettes contain macros, not just concepts

`viewpoints.xml` defines all 25 standard viewpoints, but palettes are not plain concept
lists. Seven macro placeholders expand to whole layers:

```
$BusinessElements$  $ApplicationElements$  $TechnologyElements$  $PhysicalElements$
$MotivationElements$  $StrategyElements$  $ImplementationMigrationElements$
```

A naive parse yields a two-item palette for Application Cooperation and an empty one for
Layered (empty means "all concepts allowed"). **The loader must expand these macros**, and a
palette that comes out implausibly small is a bug, not a viewpoint that happens to be narrow.

## Standing conclusion

| Aspect | Status |
|---|---|
| Element and relationship vocabulary | **Verified against a first-party Open Group source.** |
| Permitted-relationship matrix | **Not verified.** Structurally coherent and consistent with spot checks, but only the specification's Appendix B can confirm it. |
| Structural strength ordering | **Not verified.** |
| Viewpoint palettes | Taken from Archi; not verified against the specification. |

The product may not claim metamodel conformance while rows two to four say "not verified".
Closing them requires the ArchiMate 3.2 specification and stays on the phase 1 list.

## Reproducing this

The comparison is mechanical and should become an automated conformance test in phase 1, so
that a later update to either source cannot silently change the metamodel: download both
sources, diff the vocabularies, and re-run the closure measurement.
