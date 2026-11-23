---
description: Record an architecture decision as an ADR in docs/architecture/decisions - the AI Unified Process skill /architecture-decision with this project's numbering, sections, diagrams and README rows
argument-hint: <decision topic>
---
Record an ADR for: $ARGUMENTS

This command is the one way to write an ADR here. It follows the AI Unified Process skill `/architecture-decision`
(its steps "Drafting a Decision": check that it is a decision, find the `NFR-*` and `C-*` it serves, check the
accepted ADRs and the constraints, show the draft, write after the human in the loop confirms) and adds what this
project keeps for every ADR. Load the skill `choices-and-decisions` first.

1. Requirements. The ADR has a `**Requirements:**` line with the `NFR-*` and `C-*` of `docs/requirements.md` it
   serves; an `FR-*` never stands alone. When no row fits, write nothing and hand off to `/requirements`.
2. Number and name. Read `docs/architecture/decisions/README.md` and pick the next free number. The file is
   `docs/architecture/decisions/ADR-NNNN-<slug>.md`, four digits, in the format of the ADRs next to it, not the
   three-digit template of the skill.
3. Options. Compare 2-3 realistic options on simplicity, coupling, testability, operational cost,
   failure modes, security, reversibility and cognitive load for a one-person team.
4. Sections, in this order: Context, Decision, Alternatives considered, Consequences, Diagrams, Backlog. Match the
   style of the existing ADRs. Short and plain, present tense, no history. Consequences say what the decision gives
   and hold its known limits. Backlog lists the features it decides and the open issues that change it, by issue ID.
5. Diagrams, in D2 like the existing ones (see `docs/architecture/diagrams/README.md`):
   - `docs/architecture/diagrams/flow-NNNN-<slug>.d2` — flowchart of the decisions in the code
   - `docs/architecture/diagrams/sequence-NNNN-<slug>.d2` — sequence of the call path
     (no runtime path: one line in the ADR saying why, no sequence diagram)
   - if the module structure or the runtime changes: update the `architecture-*` view of the
     module that changed (and context if modules change) and/or the `infrastructure` view the change touches
     (`infrastructure.d2` the overview, `infrastructure-workloads.d2`, `infrastructure-secrets.d2`,
     `infrastructure-delivery.d2`, `infrastructure-data.d2`)
   Start each `.d2` with what it shows and the source files it describes. Render each to SVG
   (`d2 <file>.d2 <file>.svg`) and commit both.
6. README rows. Add the ADR to `docs/architecture/decisions/README.md` under its context (with the Diagrams column)
   and the diagrams to `docs/architecture/diagrams/README.md`.
7. Run the AI architecture check (`/arch-check`) on the decision, then `python3 scripts/adr-check.py`.
8. Do not commit. Show the summary of the decision, the requirements it serves and the rendered diagrams.
