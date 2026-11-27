#!/usr/bin/env python3
import json
import os
import re
import shlex
import subprocess
import sys

MERGE = "The human in the loop merges every pull request themselves. Give them the link."
PUSH_MAIN = "Nothing is pushed to main. Push the branch and the human in the loop opens the pull request."
FORCE_PUSH = "A force push needs the human in the loop's OK first. Ask them, or use --force-with-lease on your own branch."
PROD = "An assistant never changes prod. Give the human in the loop the approval link or the command."
ATTRIBUTION = "No AI attribution in a commit, pull request, issue or comment. Remove the line."
UNREADABLE = "The command line cannot be read (an open quote). Write it again."
UNKNOWN_BRANCH = "The hook cannot tell which branch this push sends. Name the branch: git push origin <branch>."

PROTECTED_BRANCHES = {"main", "master"}
PROD_VERBS = {
    "apply", "delete", "patch", "scale", "rollout", "edit", "replace", "set", "exec",
    "upgrade", "install", "rollback", "uninstall", "create", "annotate", "label",
    "drain", "cordon", "uncordon", "taint", "cp", "run", "expose", "autoscale",
}
SHELLS = {"bash", "sh", "zsh", "dash", "ksh"}
WRAPPERS = {
    "env", "sudo", "command", "nohup", "time", "exec", "nice", "xargs", "builtin",
    "timeout", "stdbuf", "ionice", "doas", "caffeinate",
}
GUARDED = {"git", "gh", "helm", "kubectl"} | {"bash", "sh", "zsh", "dash", "ksh"}
READ_ONLY_PROGRAMS = {"grep", "rg", "ag", "ack", "egrep", "fgrep"}
READ_ONLY_GIT = {"log", "show", "grep", "shortlog", "blame"}
GIT_OPTIONS_WITH_VALUE = {"-C", "-c", "--git-dir", "--work-tree", "--namespace", "--exec-path"}
PUSH_OPTIONS_WITH_VALUE = {"--repo", "-o", "--push-option", "--receive-pack", "--exec"}
ATTRIBUTION_TEXT = re.compile(r"co-authored-by|generated (with|by) \[?claude", re.IGNORECASE)
ASSIGNMENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*=.*")
SEPARATORS = "();<>|&`\n"
OPERATOR_CHARS = set(SEPARATORS)


def commands_of(line):
    lexer = shlex.shlex(line, posix=True, punctuation_chars=SEPARATORS)
    lexer.whitespace = " \t\r"
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


def unwrapped(words):
    while words:
        if ASSIGNMENT.fullmatch(words[0]):
            words = words[1:]
        elif words[0].rsplit("/", 1)[-1] in WRAPPERS:
            # A wrapper's options differ per wrapper and some take a value, so the
            # hook does not parse them: the first guarded program anywhere after
            # the wrapper is the command that runs.
            rest = words[1:]
            for i, word in enumerate(rest):
                if word.rsplit("/", 1)[-1] in GUARDED | WRAPPERS:
                    words = rest[i:]
                    break
            else:
                return []
        else:
            break
    return words


def git_subcommand(words):
    """Returns (directory from -C or None, repository options, subcommand, its arguments)."""
    directory = None
    repository_options = []
    i = 1
    while i < len(words):
        word = words[i]
        if word.startswith(("--git-dir=", "--work-tree=")):
            repository_options.append(word)
            i += 1
        elif word in GIT_OPTIONS_WITH_VALUE:
            if word == "-C" and i + 1 < len(words):
                directory = words[i + 1]
            if word in {"--git-dir", "--work-tree"} and i + 1 < len(words):
                repository_options += [word, words[i + 1]]
            i += 2
        elif word.startswith("-"):
            i += 1
        else:
            return directory, repository_options, word, words[i + 1:]
    return directory, repository_options, None, []


UNKNOWN = object()


def current_branch(directory, git_options):
    """The checked-out branch, None when HEAD is detached, UNKNOWN when git cannot tell."""
    try:
        result = subprocess.run(
            ["git", "-C", directory, *git_options, "symbolic-ref", "--short", "-q", "HEAD"],
            capture_output=True, text=True, timeout=3,
        )
    except (OSError, subprocess.SubprocessError):
        return UNKNOWN
    if result.returncode == 1:
        return None
    if result.returncode != 0:
        return UNKNOWN
    return result.stdout.strip() or None


def branch_name(refspec):
    name = refspec.split(":")[-1].lstrip("+")
    if name.startswith("refs/heads/"):
        name = name[len("refs/heads/"):]
    return name.lower()


def is_force_flag(arg):
    if arg in {"--force", "-f"}:
        return True
    return arg.startswith("-") and not arg.startswith("--") and "f" in arg[1:]


def push_targets(args):
    targets = []
    skip = False
    for arg in args:
        if skip:
            skip = False
        elif arg in PUSH_OPTIONS_WITH_VALUE:
            skip = True
        elif not arg.startswith("-"):
            targets.append(arg)
    return targets


def refusal_of_git(words, cwd):
    directory, repository_options, subcommand, args = git_subcommand(words)
    if subcommand != "push":
        return None
    if directory:
        cwd = os.path.join(cwd, directory)
    if any(a in {"--all", "--mirror"} for a in args):
        return PUSH_MAIN
    refspecs = push_targets(args)[1:]
    branches = [branch_name(r) for r in refspecs]
    if not refspecs or "head" in branches:
        branch = current_branch(cwd, repository_options)
        if branch is UNKNOWN:
            return UNKNOWN_BRANCH
        if branch and branch.lower() in PROTECTED_BRANCHES:
            return PUSH_MAIN
    if any(b in PROTECTED_BRANCHES for b in branches):
        return PUSH_MAIN
    if any(is_force_flag(a) for a in args) or any(r.startswith("+") for r in refspecs):
        return FORCE_PUSH
    return None


def refusal_of_gh(words):
    if len(words) > 2 and words[1] in {"pr", "stack"} and words[2] == "merge":
        return MERGE
    if len(words) > 1 and words[1] == "api" and any(w.rstrip("/").endswith("/merge") for w in words[2:]):
        return MERGE
    return None


def refusal_of_cluster(words):
    if any("prod" in w for w in words[1:]) and any(w in PROD_VERBS for w in words[1:]):
        return PROD
    return None




def refusal_of_command(words, cwd, line):
    words = unwrapped(words)
    if not words:
        return None
    program = words[0].rsplit("/", 1)[-1]
    if program in SHELLS:
        for i, word in enumerate(words[1:], start=1):
            if word.startswith("-") and not word.startswith("--") and "c" in word:
                return refusal(words[i + 1], cwd) if i + 1 < len(words) else None
        return None
    if program == "gh":
        return refusal_of_gh(words)
    if program == "git":
        return refusal_of_git(words, cwd)
    if program in {"helm", "kubectl"}:
        return refusal_of_cluster(words)
    return None


def only_reads(commands):
    for words in commands:
        words = unwrapped(words)
        if not words or words[0] == "cd":
            continue
        program = words[0].rsplit("/", 1)[-1]
        if program in READ_ONLY_PROGRAMS:
            continue
        if program == "git" and git_subcommand(words)[2] in READ_ONLY_GIT:
            continue
        return False
    return True


def refusal(line, cwd="."):
    try:
        commands = list(commands_of(line))
    except ValueError:
        return UNREADABLE
    if ATTRIBUTION_TEXT.search(line) and not only_reads(commands):
        return ATTRIBUTION
    for words in commands:
        if words and words[0] == "cd" and len(words) > 1:
            cwd = os.path.join(cwd, os.path.expanduser(words[1]))
            continue
        message = refusal_of_command(words, cwd, line)
        if message:
            return message
    return None


def self_test():
    import tempfile

    with tempfile.TemporaryDirectory() as root:
        for branch in ("main", "fix-x"):
            repo = os.path.join(root, branch)
            subprocess.run(["git", "init", "-q", "-b", branch, repo], check=True)
        blocked = [
            "gh pr merge 12 --squash",
            "cd x && gh stack merge",
            "gh api -X PUT repos/o/r/pulls/3/merge",
            "git push origin main",
            "git push origin HEAD:main",
            "git -C ../x push origin +fix/x:main",
            "git push -f origin fix/x",
            "git push -uf origin fix/x",
            "git push --force origin fix/x",
            "git push origin +fix/x",
            "git push --all origin",
            "git push --mirror origin",
            f"git -C {root}/main push",
            f"cd {root}/main && git push origin HEAD",
            f"cd {root}/main && git push -u origin",
            "helm upgrade searchavo deploy/helm -n searchavo-prod",
            "kubectl -n searchavo-prod delete pod x",
            "kubectl delete pod x -n searchavo-prod",
            "kubectl create secret generic x -n searchavo-prod",
            "git push origin refs/heads/main",
            "git push origin HEAD:refs/heads/main",
            "git push origin Main",
            f"git -C {root}/nowhere push",
            "sudo -u root git push origin main",
            "env -u HOME gh pr merge 3",
            "timeout 10 gh pr merge 3",
            "nice -n 5 git push origin main",
            "xargs -I {} gh pr merge {}",
            "sudo --user root git push origin main",
            "sudo --user=root git push origin main",
            "xargs --max-procs 4 gh pr merge",
            "caffeinate -t 60 git push origin main",
            "stdbuf --output L gh pr merge 3",
            "env -S 'x' sudo -g wheel git push origin main",
            "sudo -u root bash -c 'gh pr merge 3'",
            "curl -d 'Co-Authored-By: Claude' https://api.github.com/x",
            "SA_ENV=x gh pr merge 3",
            "env gh pr merge 3",
            "sudo -E git push origin main",
            'bash -c "gh pr merge 3"',
            'bash -lc "gh pr merge 3"',
            "echo ok; gh pr merge 3",
            "echo ok\ngh pr merge 3",
            "echo `gh pr merge 3`",
            "echo $(gh pr merge 3)",
            'git commit -m "fix\n\nCo-Authored-By: someone"',
            'gh pr create --body "Generated with [Claude Code](x)"',
            'gh pr create --body "open quote',
        ]
        allowed = [
            "git push origin fix/x",
            "git push --force-with-lease origin fix/x",
            "git push -u origin docs/main-page",
            f"git -C {root}/fix-x push",
            f"cd {root}/fix-x && git push -u origin HEAD",
            "git log --grep push main",
            "git commit -m 'push the main menu fix'",
            "gh pr view 12 --json mergeable",
            'gh pr create --body "the human in the loop: gh pr merge 12 --squash --delete-branch"',
            "git commit -m 'docs: how to run git push origin main by hand'",
            'git commit -m "line one\nline two"',
            "helm upgrade searchavo deploy/helm -n searchavo-staging",
            "kubectl get pods -n searchavo-prod",
            "git commit -m 'fix(search): the merge keeps the best quote'",
            "git merge --no-edit origin/main",
            "git log --grep Co-Authored-By",
            "grep -ri co-authored-by .github",
            "sudo -u root ls /",
            "timeout 30 git push origin fix/x",
        ]
        wrong = [c for c in blocked if refusal(c, root) is None]
        wrong += [c for c in allowed if refusal(c, os.path.join(root, "fix-x"))]
    for command in wrong:
        print("WRONG:", repr(command))
    print("self-test:", "failed" if wrong else "passed")
    return 1 if wrong else 0


def main():
    if "--self-test" in sys.argv:
        return self_test()
    try:
        payload = json.load(sys.stdin)
        command = payload.get("tool_input", {}).get("command", "")
        cwd = payload.get("cwd") or os.getcwd()
    except (ValueError, AttributeError):
        return 0
    message = refusal(command or "", cwd)
    if message:
        print("Blocked by .claude/hooks/guard-bash.py: " + message, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
