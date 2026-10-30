---
description: Draft a new ADR in docs/adr for a decision
argument-hint: <decision topic>
---
Draft an ADR for: $ARGUMENTS

1. Read `docs/adr/README.md` and pick the next free number.
2. Compare 2-3 realistic options on simplicity, coupling, testability, operational cost,
   failure modes, security, reversibility and cognitive load for a one-person team.
3. Write `docs/adr/NNNN-<slug>.md` with: Context, Decision, Alternatives, Consequences, Diagrams.
   Match the style of the existing ADRs. Short and plain.
4. Diagrams, in D2 like the existing ones (see `docs/diagrams/README.md`):
   - `docs/diagrams/flow-NNNN-<slug>.d2` — flowchart of the decisions in the code
   - `docs/diagrams/sequence-NNNN-<slug>.d2` — sequence of the call path
     (no runtime path: one line in the ADR saying why, no sequence diagram)
   - if the module structure or the runtime changes: update `software-architecture.d2`
     and/or `infrastructure.d2`
   Start each `.d2` with what it shows and the source files it describes. Render each to SVG
   (`d2 <file>.d2 <file>.svg`) and commit both.
5. Add the ADR to `docs/adr/README.md` (with the Diagrams column) and the diagrams to
   `docs/diagrams/README.md`.
6. Do not commit. Show the summary of the decision and the rendered diagrams.
