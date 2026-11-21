#!/usr/bin/env python3
"""PreToolUse hook for Bash: stops the commands AGENTS.md says an assistant never runs.

The deny rules in .claude/settings.json match the usual spelling of a command. This hook reads the
whole command line, so a chained or re-ordered form is stopped too. Exit 2 blocks the call and
shows the message to the assistant. Run `guard-bash.py --self-test` to check the rules.
"""

import json
import re
import sys

PROD_VERBS = r"(apply|delete|patch|scale|rollout|edit|replace|set|exec|upgrade|install|rollback|uninstall)"

RULES = [
    (
        r"\bgh\s+(pr|stack)\s+merge\b|\bgh\s+api\b.*/merge\b",
        "Silvio merges every pull request himself. Give him the link.",
    ),
    (
        r"\bgit\s+push\b.*(\s|:)(main|master)(\s|$)",
        "Nothing is pushed to main. Push the branch and Silvio opens the pull request.",
    ),
    (
        r"\bgit\s+push\b.*(\s--force(?!-with-lease)\b|\s-f\b|\s\+\S)",
        "A force push needs Silvio's OK first. Ask him, or use --force-with-lease on your own branch.",
    ),
    (
        r"\b(helm|kubectl)\b(?=.*\bprod)(?=.*\s" + PROD_VERBS + r"(\s|$))",
        "An assistant never changes prod. Give Silvio the approval link or the command.",
    ),
    (
        r"co-authored-by|generated (with|by) \[?claude",
        "No AI attribution in a commit, pull request, issue or comment. Remove the line.",
    ),
]


def refusal(command):
    for pattern, message in RULES:
        if re.search(pattern, command, re.IGNORECASE | re.DOTALL):
            return message
    return None


def self_test():
    blocked = [
        "gh pr merge 12 --squash",
        "cd x && gh stack merge",
        "gh api -X PUT repos/o/r/pulls/3/merge",
        "git push origin main",
        "git push origin HEAD:main",
        "git push -f origin fix/x",
        "git push --force origin fix/x",
        "helm upgrade helper2 deploy/helm -n helper2-prod",
        "kubectl -n helper2-prod delete pod x",
        "kubectl delete pod x -n helper2-prod",
        'git commit -m "fix\n\nCo-Authored-By: someone"',
        'gh pr create --body "Generated with [Claude Code](x)"',
    ]
    allowed = [
        "git push origin fix/x",
        "git push --force-with-lease origin fix/x",
        "git push -u origin docs/main-page",
        "gh pr view 12 --json mergeable",
        "helm upgrade helper2 deploy/helm -n helper2-staging",
        "kubectl get pods -n helper2-prod",
        "git commit -m 'fix(search): the merge keeps the best quote'",
    ]
    wrong = [c for c in blocked if refusal(c) is None] + [c for c in allowed if refusal(c)]
    for command in wrong:
        print("WRONG:", command)
    print("self-test:", "failed" if wrong else "passed")
    return 1 if wrong else 0


def main():
    if "--self-test" in sys.argv:
        return self_test()
    try:
        command = json.load(sys.stdin).get("tool_input", {}).get("command", "")
    except (ValueError, AttributeError):
        return 0
    message = refusal(command or "")
    if message:
        print("Blocked by .claude/hooks/guard-bash.py: " + message, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
