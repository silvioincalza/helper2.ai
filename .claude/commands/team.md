---
description: Solve a task with the specialist agents working together, with the human in the loop in the loop
argument-hint: <task>
---
Task: $ARGUMENTS

0. Name the `UC-*` the task builds or changes (`docs/use_cases/<module>/`). No use case for new behaviour: write it
   first with `/use-case-spec`. Its `**Status:**` is not `Approved`: say so; the human in the loop approves before the build.
1. Pick only the specialists this task needs (architect, backlog, metasearch, performance, security,
   privacy-legal, ux-journeys, ui-design). Say which and why in one line each.
2. Run them in parallel. Give each the task and what the others are looking at.
3. Bring their findings together. Where they disagree, show the conflict and your recommendation.
4. STOP. Present to the human in the loop: the plan, the trade-offs, the open questions. Explain in simple words
   first, then the technical detail. Wait for his decision.
5. After his OK: implement with tests that name the `UC-*`, run `verifier`, then `reviewer` (and security or any
   other specialist the change touches) on the result. Before the pull request opens, run backlog for its story and the
   `Closes` lines. Report what changed and what is still open.
6. Docs: write them in the human in the loop's voice and in the style of the existing docs. No AI attribution.
   Working notes and reviews go in `.private/`, never in the repo.
