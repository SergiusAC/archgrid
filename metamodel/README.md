# Metamodel

This directory will hold `archimate-3.2.yaml` — the machine-readable ArchiMate 3.2
metamodel that ArchGrid loads at startup: element types, relationship types, the
structural-strength ordering used for relationship derivation, the permitted-relationship
matrix, viewpoints with their palettes, and the C4 ↔ ArchiMate type mapping.

It is empty in phase 0 by design. Filling it is the whole of **phase 1**, and the rule that
governs it is not negotiable (see [ADR-0002](../docs/adr/0002-metamodel-as-data.md)):

> The permitted-relationship matrix and the strength ordering are transcribed from the
> official ArchiMate 3.2 specification, Appendix B. Not from memory, not from an LLM, not
> from a third-party repository without line-by-line verification.

An error in this file makes the product give an architect a false guarantee about the
correctness of their model. That is the one failure the target user does not forgive, and
no amount of polish elsewhere compensates for it.

## Planned layout

```
metamodel/
├── archimate-3.2.yaml        # the metamodel itself
├── checksum.txt              # verified at startup; a mismatch fails the boot
└── conformance/              # control cases taken from the specification, used as tests
```

## Why data and not code

A 60×60 matrix expressed as conditional statements is untestable and unmaintainable. As
data it can be validated automatically for completeness, internal contradiction and
unresolved references — and extended with new viewpoints or notation mappings without a
release.
