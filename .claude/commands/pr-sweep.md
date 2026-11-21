---
description: "Bring a pull request to \"only the human in the loop's approval is missing\": fix review comments and failing checks"
argument-hint: <pull request number>
---
Sweep pull request #$ARGUMENTS until only the human in the loop's review is left. Never merge it.

1. Read it: `gh pr view $ARGUMENTS --json headRefName,baseRefName,mergeable,reviewDecision,statusCheckRollup` and
   `gh pr view $ARGUMENTS --comments`. Work in the worktree of its branch (`.claude/worktrees/<branch>`).
2. Update the branch first: `git fetch origin`, then merge its base branch in. Resolve every conflict by reading both
   sides; when the two sides disagree on behaviour, stop and ask the human in the loop.
3. Failing checks: read the failing job (`gh run view --log-failed`), reproduce it with `scripts/verify.sh`, fix the
   cause. Never skip, loosen or delete a test to turn a check green.
4. Review comments (people and automated reviews): fix each one, or say in one line why it is not a defect.
   A comment that changes the design or the scope goes to the human in the loop first.
5. Run the `verifier` agent (`scripts/verify.sh` must pass), then push the branch (never to main, never a force push without the human in the loop's OK).
6. Repeat from step 3 until the checks are green and no comment is open.
7. Do not post on GitHub: give the human in the loop the reply for each comment as text, and the link to the pull request.

Report: what you fixed, what you left and why, what waits for the human in the loop.
