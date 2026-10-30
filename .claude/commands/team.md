---
description: Solve a task with the specialist agents working together, with Silvio in the loop
argument-hint: <task>
---
Task: $ARGUMENTS

1. Pick only the specialists this task needs (architect, metasearch, performance, security,
   privacy-legal, ux-journeys). Say which and why in one line each.
2. Run them in parallel. Give each the task and what the others are looking at.
3. Bring their findings together. Where they disagree, show the conflict and your recommendation.
4. STOP. Present to Silvio: the plan, the trade-offs, the open questions. Explain in simple words
   first, then the technical detail. Wait for his decision.
5. After his OK: implement with tests, then ask security (and any other specialist the change
   touches) to review the result. Report what changed and what is still open.
6. Docs: write them in Silvio's voice and in the style of the existing docs. No AI attribution.
   Working notes and reviews go in `.private/`, never in the repo.
