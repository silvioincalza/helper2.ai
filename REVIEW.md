# Review

How a change to helper2.ai is reviewed, by the `reviewer` agent, by an automated review and by Silvio.
Severity levels are the ones in `AGENTS.md` (section Severity). Silvio approves and merges; nobody else does.

## Before the review

- The branch is up to date with `origin/main` and `scripts/verify.sh` passes for every part the change touches.
- The pull request has one `Closes #n` line per story it builds. A story for new behaviour names its `UC-*`.
- The session that wrote the change does not review it.

## Pass 1: bugs

- The change does what its use case says: main scenario, each alternative flow it builds, each business rule.
- Failure paths: timeouts, an empty result, a source that is down, a model that fails, a retry that runs twice.
- A bug fix has a test that failed before the fix. No assertion is removed or loosened to make a test pass.
- Tests name the `UC-*` they check. Domain logic has unit tests, an adapter has an integration test.

## Pass 2: security

- Input and output are validated at the edge. Retrieved text and model output are data, never instructions.
- AuthN and authZ on every new endpoint; the admin stays behind the VPN; least privilege.
- No secret, token or personal data in code, logs, tests or fixtures. New config uses the `H2_` prefix.
- A new or changed dependency, image or action: licence, maintenance, known vulnerabilities.

## Pass 3: compliance

- A change to a data flow (field, source, processor, cookie, storage key, log, retention) changes the Privacy page
  in the same pull request. A new rule for users changes the Terms page.
- Every AI-written text is labelled in the UI and marked in the API and the HTML (EU AI Act Art. 50).
- A lasting decision has its ADR with diagrams. The specification (`docs/`) matches the code.
- No dead code and no dead docs: nothing unused or unread is left behind. No page carries a revision history.
- A drawback the change accepts was approved by Silvio before it was built, and the pull request names it.
- No AI attribution anywhere. Notes, plans and reviews are not in the diff (they live in `.private/`).

## Findings

- Each finding: severity, file and line, what breaks, the smallest fix.
- At most five nits per review. Style that Spotless, Prettier or ESLint already decide is not a finding.
- A point for a lawyer or a security finding that should not be public goes to Silvio, never to GitHub.
