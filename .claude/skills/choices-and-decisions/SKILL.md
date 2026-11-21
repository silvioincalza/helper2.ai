---
name: choices-and-decisions
description: Use before adding or upgrading a library, a tool or a version, before comparing architecture options, and before writing or changing an ADR or its diagrams (the /adr command). Holds the rules for technology choices, architecture choices, the ADR sections and the flowchart and sequence diagram every ADR has.
---

# Choices and decisions

- Versions: use the latest compatible release of every dependency and tool. Ask the human in the loop before
  picking a version, and say why when the latest does not work.
- Before adding a library or tool: compare 2-3 candidates (fit, maintenance, licence, lock-in,
  operational cost), recommend one, state the trade-off. Revisit an existing feature's choice
  only when the feature changes or has a real problem.
- Architecture choices: compare 2-3 realistic options on simplicity, coupling, testability,
  operational cost, failure modes, security, reversibility and cognitive load for a one-person
  team. Recommend one.
- Lasting decision → new ADR `docs/architecture/decisions/ADR-NNNN-<slug>.md` (next number), linked from
  `docs/architecture/decisions/README.md` under its context. One ADR per decision, numbered in the order the system
  is built, grouped by context in its README. The `/adr` command writes it.
  It states the decision in force, in the present tense, as the code is, with these sections in this order: Context,
  Decision, Alternatives considered, Consequences, Diagrams, Backlog. Known limits go in its Consequences. Its
  Backlog section lists the features it decides and the open issues that change it.
- An ADR names the `NFR-*` and `C-*` it serves on its `**Requirements:**` line.
- ADRs and code stay free of history: an ADR that no longer holds is `Superseded`.
- Open work is a backlog issue. Docs and ADRs point at issue IDs (`S10.12.1`), never at a roadmap section.
- Every piece of work has a story on the Project board before its pull request opens. The `backlog` agent finds
  it, or creates it with every attribute (ID, story line, acceptance criteria, Context, Priority, parent feature),
  sets its Status to In Progress and gives the `Closes #n` line. No pull request without a story.
- Every ADR has a flowchart (the decisions in the code) and a sequence diagram (the call path),
  as D2 sources in `docs/architecture/diagrams/` (`flow-NNNN-<slug>.d2`, `sequence-NNNN-<slug>.d2`) with the
  rendered SVG committed next to them, linked from the ADR and both README indexes. When the
  decision changes the module structure or the runtime, also update the `architecture-*` view of the
  module that changed (and `architecture-context` if modules change) and/or the `infrastructure` views (the overview
  and `infrastructure-workloads`, `-secrets`, `-delivery`, `-data`: the one the change touches). A decision with no
  runtime path (e.g. versioning) says so in one line instead of a sequence diagram. Each `.d2` names the code it
  describes; update it in the same change as that code.
- Every diagram the process writes in PlantUML or Mermaid also has a D2 twin with its SVG and PNG in
  `docs/architecture/diagrams/` (skill `aiup-diagrams`).
- Architecture or design work → run the AI architecture check (skill `ai-architecture-check`, command `/arch-check`).
