---
name: artifacts-and-commands
description: Use before creating or changing any page under docs/ (requirements, glossary, use cases, use case diagrams, entity model, test cases, open questions, stack file, ADRs, diagrams, roadmap, mockups, architecture pages), and when asked which command makes or checks an artifact. Holds the table artifact → command: one command makes each artifact.
---

# Artifacts and the command that makes each

One command makes each artifact. The commands without a file in `.claude/commands/` are the skills of the AI Unified
Process (`aiup-core`). `/next` reads the state of `docs/` and names the next command; it writes nothing.

| Artifact | Command |
|---|---|
| `docs/requirements.md`, `docs/glossary.md` | `/requirements` (from `docs/vision.md`) |
| `docs/use_cases.puml`, `docs/use_cases/<module>/use_cases.puml` | `/use-case-diagram` |
| `docs/use_cases/<module>/UC-*.md` | `/use-case-spec` |
| `docs/entity_model.md`, `docs/entity_model/<module>.md` | `/entity-model` |
| `docs/test_cases/` (made by the first run) | `/test-case` |
| `docs/open_questions.md` | `/open-questions` |
| `docs/architecture/stack.md` | `/stack` |
| An ADR in `docs/architecture/decisions/`, its README row, its flowchart and sequence diagram | `/adr` |
| The D2 twin, SVG and PNG of a PlantUML or Mermaid diagram | skill `aiup-diagrams` (`docs/architecture/diagrams/render.sh --png`) |
| `docs/roadmap.md` | `python3 scripts/backlog.py roadmap` |
| The issues and the Project board | the `backlog` agent (`python3 scripts/backlog.py import`) |
| The code of a use case | `/use-case-implement` |
| The tests of a use case | `/use-case-test` |

Written by hand, in the pull request that changes what the page describes, because no command makes them:

- `docs/vision.md`: the human in the loop writes it.
- `docs/mockups/`: the screen notes of a use case; the `ux-journeys` agent returns the text.
- `docs/architecture/backlog.md`, `costs.md`, `data-processing.md`, `sources.md`, `test-map.md`, `ai-literacy.md`,
  and the `architecture-*` and `infrastructure*` views in `docs/architecture/diagrams/`.

Until the migration to the AI Unified Process is complete (the issues, the Project, the backlog and `docs/` all
follow it: epic E11), every page is written as the final text: no revision table, no version or change log in a
page, no "previously" or "moved from". When the human in the loop says the migration is complete, revision history starts in
the specification pages only: `docs/vision.md`, `docs/requirements.md`, the use cases and the entity model.
ADRs and code stay free of history, then as now: an ADR that no longer holds is `Superseded`.

A requirement change is checked against the entity model and the use case diagrams in the same change. They are
updated when it adds or changes an entity, an actor or a use case; when it changes none, the pull request says so.

Commands that check and write no artifact:

| Check | Command |
|---|---|
| The specification against itself | `/spec-review` |
| A built use case against its specification | `/use-case-coverage` |
| The 15 layers of the AI architecture check | `/arch-check` (this project's own; no AI Unified Process skill covers it) |
| What CI runs | `scripts/verify.sh <part>` (the `verifier` agent) |
| A change against `REVIEW.md` | the `reviewer` agent |

Commands for the flow of work, with no AI Unified Process counterpart: `/team` (specialists on one task),
`/pr-sweep` (review comments and failing checks), `/sync-worktrees` (worktrees after a merge).
