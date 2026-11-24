#!/usr/bin/env python3
import json
import re
import shlex
import sys

MERGE = "The human in the loop merges every pull request himself. Give him the link."
PUSH_MAIN = "Nothing is pushed to main. Push the branch and the human in the loop opens the pull request."
FORCE_PUSH = "A force push needs the human in the loop's OK first. Ask him, or use --force-with-lease on your own branch."
PROD = "An assistant never changes prod. Give the human in the loop the approval link or the command."
ATTRIBUTION = "No AI attribution in a commit, pull request, issue or comment. Remove the line."
UNREADABLE = "The command line cannot be read (an open quote). Write it again."

PROTECTED_BRANCHES = {"main", "master"}
PROD_VERBS = {
    "apply", "delete", "patch", "scale", "rollout", "edit", "replace", "set", "exec",
    "upgrade", "install", "rollback", "uninstall",
}
SHELLS = {"bash", "sh", "zsh"}
ATTRIBUTION_TEXT = re.compile(r"co-authored-by|generated (with|by) \[?claude", re.IGNORECASE)
ASSIGNMENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*=.*")
OPERATOR_CHARS = set("();<>|&")


def commands_of(line):
    lexer = shlex.shlex(line, posix=True, punctuation_chars=True)
    lexer.whitespace_split = True
    command = []
    for token in lexer:
        if token and set(token) <= OPERATOR_CHARS:
            if command:
                yield command
            command = []
        else:
            command.append(token)
    if command:
        yield command


def without_assignments(words):
    while words and ASSIGNMENT.fullmatch(words[0]):
        words = words[1:]
    return words


def refusal_of_gh(words):
    if len(words) > 2 and words[1] in {"pr", "stack"} and words[2] == "merge":
        return MERGE
    if len(words) > 1 and words[1] == "api" and any(w.rstrip("/").endswith("/merge") for w in words[2:]):
        return MERGE
    return None


def refusal_of_git(words):
    if "push" not in words:
        return None
    args = words[words.index("push") + 1:]
    targets = [a for a in args if not a.startswith("-")]
    if any(t.split(":")[-1].lstrip("+") in PROTECTED_BRANCHES for t in targets):
        return PUSH_MAIN
    if any(a in {"--force", "-f"} for a in args) or any(t.startswith("+") for t in targets):
        return FORCE_PUSH
    return None


def refusal_of_cluster(words):
    if any("prod" in w for w in words[1:]) and any(w in PROD_VERBS for w in words[1:]):
        return PROD
    return None


def refusal_of_command(words):
    words = without_assignments(words)
    if not words:
        return None
    program = words[0].rsplit("/", 1)[-1]
    if program in SHELLS and "-c" in words:
        script = words[words.index("-c") + 1:]
        return refusal(script[0]) if script else None
    if program == "gh":
        return refusal_of_gh(words)
    if program == "git":
        return refusal_of_git(words)
    if program in {"helm", "kubectl"}:
        return refusal_of_cluster(words)
    return None


def refusal(line):
    if ATTRIBUTION_TEXT.search(line):
        return ATTRIBUTION
    try:
        commands = list(commands_of(line))
    except ValueError:
        return UNREADABLE
    for words in commands:
        message = refusal_of_command(words)
        if message:
            return message
    return None


def self_test():
    blocked = [
        "gh pr merge 12 --squash",
        "cd x && gh stack merge",
        "gh api -X PUT repos/o/r/pulls/3/merge",
        "git push origin main",
        "git push origin HEAD:main",
        "git -C ../x push origin +fix/x:main",
        "git push -f origin fix/x",
        "git push --force origin fix/x",
        "git push origin +fix/x",
        "helm upgrade searchavo deploy/helm -n searchavo-prod",
        "kubectl -n searchavo-prod delete pod x",
        "kubectl delete pod x -n searchavo-prod",
        "SA_ENV=x gh pr merge 3",
        'bash -c "gh pr merge 3"',
        "echo ok; gh pr merge 3",
        'git commit -m "fix\n\nCo-Authored-By: someone"',
        'gh pr create --body "Generated with [Claude Code](x)"',
        'gh pr create --body "open quote',
    ]
    allowed = [
        "git push origin fix/x",
        "git push --force-with-lease origin fix/x",
        "git push -u origin docs/main-page",
        "gh pr view 12 --json mergeable",
        'gh pr create --body "the human in the loop: gh pr merge 12 --squash --delete-branch"',
        "git commit -m 'docs: how to run git push origin main by hand'",
        "helm upgrade searchavo deploy/helm -n searchavo-staging",
        "kubectl get pods -n searchavo-prod",
        "git commit -m 'fix(search): the merge keeps the best quote'",
        "git merge --no-edit origin/main",
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
