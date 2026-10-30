---
description: Draft a new ADR in docs/adr for a decision
argument-hint: <decision topic>
---
Draft an ADR for: $ARGUMENTS

1. Read `docs/adr/README.md` and pick the next free number.
2. Compare 2-3 realistic options on simplicity, coupling, testability, operational cost,
   failure modes, security, reversibility and cognitive load for a one-person team.
3. Write `docs/adr/NNNN-<slug>.md` with: Context, Decision, Alternatives, Consequences.
   Match the style of the existing ADRs. Short and plain.
4. Add it to the index in `docs/adr/README.md`.
5. Do not commit. Show the summary of the decision.
