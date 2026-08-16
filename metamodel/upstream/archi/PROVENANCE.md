# Vendored upstream: Archi metamodel data

These files are **not written by this project**. They are copied verbatim from
[archimatetool/archi](https://github.com/archimatetool/archi), pinned to a specific commit,
and are the source from which `metamodel/archimate-3.2.yaml` is generated.

| Field | Value |
|---|---|
| Repository | https://github.com/archimatetool/archi |
| Path | `com.archimatetool.model/model/` |
| Commit | `ccb3fd650f23acc6d5f3ad77fa9de809a5fc2f5a` |
| Retrieved | 2026-08-17 |
| Licence | MIT — see `LICENSE.txt` in this directory |
| Copyright | Phillip Beauvoir, Jean-Baptiste Sarrodie, The Open Group |

## Checksums

```
65ad5b5c8044fc62c61836a702d0bd2e0427a87216d7f5e397666c74508d57c5  relationships.xml
19d86924efc3364d26c162fe4e6643f37fd9493a0effc80ce5e2aa648fb6803e  relationships-keys.xml
a101699683fe7a92e4f62d6a389712a92fe2792fe36cb2a0614815aca21211c8  viewpoints.xml
a8dea91c843a5a2f8c7c62288dcd82641c21b8a3892836aaa11a9d455afac426  archimate.ecore
428f4272e912bbc3809052990c5ba1089b06382dab6e6d6a71670789bfc9bcff  LICENSE.txt
```

## What each file contributes

| File | Contributes |
|---|---|
| `relationships.xml` | The permitted-relationship matrix, 62 × 62, declared `version="3.2"` |
| `relationships-keys.xml` | The 11 relationship types and their single-letter keys used by the matrix |
| `viewpoints.xml` | The 25 standard viewpoints and their palettes (containing macros — see below) |
| `archimate.ecore` | Layer and aspect for every element type, and category for every relationship type, encoded as `eSuperTypes` |

## Why vendored rather than fetched

Two reasons, both non-negotiable for this product:

1. **Air-gapped installs.** Nothing in the build or the runtime may require network access.
2. **Reproducibility.** A metamodel that silently changes when upstream changes is worse than
   no metamodel. The commit is pinned and the checksums are verified by the build.

## Updating

Do not edit these files. To take a newer upstream version: re-download at a new commit,
update the commit, date and checksums above, run `./gradlew generateMetamodel`, and read the
diff of `metamodel/archimate-3.2.yaml` before committing it. The diff is the point of this
arrangement — an unexplained change in the matrix is a defect, not an update.

## Verification status

See [`../VERIFICATION.md`](../VERIFICATION.md). Vocabulary is verified against The Open Group
exchange-format XSD. The matrix itself, the structural strength ordering and the viewpoint
palettes are **accepted on the authority of Archi**, by an explicit decision recorded in
[ADR-0002](../../docs/adr/0002-metamodel-as-data.md) — they have not been checked against the
specification's Appendix B.

## Attribution obligation

The MIT licence requires the copyright notice and permission notice to accompany
redistribution. `LICENSE.txt` must be carried into any ArchGrid distribution that ships the
generated metamodel, and Archi must be named in the product's third-party notices.
