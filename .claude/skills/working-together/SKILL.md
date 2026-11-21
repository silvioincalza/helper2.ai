---
name: working-together
description: Use when planning a task, before asking or reporting to the human in the loop, before writing documentation, when review comments wait on a pull request, and when more than one specialist agent works on a task (the /team command). Holds the rules for working with the human in the loop: when to wait, when to keep going, when to stop and ask, how to explain and how to write docs.
---

# Working with the human in the loop

- Specialist agents (`.claude/agents/`: architect, backlog, metasearch, performance, security,
  privacy-legal, ux-journeys, ui-design) can work on one task together. One coordinator gives them the task,
  shares their findings, resolves conflicts and merges the result. Use several agents only for
  work that crosses roles; a small task stays with one agent.
- A human is always in the loop: present the plan, trade-offs and open questions, and wait for his
  decision before implementing anything that is not trivial or that is hard to undo.
- A choice with a drawback needs the human in the loop's approval before it is done: something lost, weakened, made slower, made
  dearer, left out or done differently from what he asked. Say the drawback and what it costs, then wait.
  Reporting it after the fact is not approval.
- Keep working when the next step does not need the human in the loop's input. Do not stop to ask for a go-ahead
  on routine steps.
- Stop and ask the human in the loop before:
  - deleting important data;
  - force-pushing (this includes `--force-with-lease` after a rebase);
  - modifying external systems;
  - performing destructive operations;
  - making a decision that needs his approval.
- Fix the review comments on a PR (automated review findings included) without waiting to be asked.
  `/pr-sweep <n>` fixes review comments and failing checks until only the human in the loop's approval is missing.
- Explain everything in simple words first, then the technical detail.
- Write documentation as the human in the loop would write it: his voice, plain short sentences, the style and
  structure of the existing docs.
