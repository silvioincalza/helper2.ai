---
description: Solve a task with the specialist agents working together on the AI Unified Process steps, with the human in the loop in the loop
argument-hint: <task>
---
Task: $ARGUMENTS

The coordinator flow. Each step that makes an artifact calls the AI Unified Process skill that makes it (skill
`artifacts-and-commands`); the specialists advise, they do not replace a skill. Load the skill `working-together`.

0. State. Run `/next` to see where the specification stands. Name the `UC-*` the task builds or changes
   (`docs/use_cases/<module>/`). No use case for new behaviour: write it first with `/use-case-spec`. Its
   `**Status:**` is not `Approved`: say so; the human in the loop approves before the build. A gap or a contradiction
   goes to `/open-questions`.
1. Pick only the specialists this task needs (architect, backlog, metasearch, performance, security,
   privacy-legal, ux-journeys, ui-design). Say which and why in one line each.
2. Run them in parallel. Give each the `UC-*`, the task and what the others are looking at.
3. Bring their findings together. Where they disagree, show the conflict and your recommendation. A lasting
   decision is an ADR: `/adr`. When the specification changed, run `/spec-review`.
4. STOP. Present to the human in the loop: the plan, the trade-offs, the open questions. Explain in simple words
   first, then the technical detail. Wait for his decision.
5. After his OK: build with `/use-case-implement` and test with `/use-case-test` (tests name the `UC-*`); work with
   no use case (a bug fix, tooling, docs) is built with its tests directly. Run `verifier`, then `reviewer` (and
   security or any other specialist the change touches) on the result. Open the pull request yourself once
   `scripts/verify.sh` passes; before it opens, run backlog for its story and the `Closes` lines. Report what
   changed and what is still open.
6. Docs: write them in the human in the loop's voice and in the style of the existing docs. No AI attribution.
   Working notes and reviews go in `.private/`, never in the repo.
