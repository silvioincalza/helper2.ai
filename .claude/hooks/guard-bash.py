#!/usr/bin/env python3
import json
import os
import re
import shlex
import shutil
import subprocess
import sys

MERGE = "The human in the loop merges every pull request themselves. Give them the link."
PUSH_MAIN = "Nothing is pushed to main. Push the branch and the human in the loop opens the pull request."
FORCE_PUSH = "A force push needs the human in the loop's OK first. Ask them, or use --force-with-lease on your own branch."
PROD = "An assistant never changes prod. Give the human in the loop the approval link or the command."
ATTRIBUTION = "No AI attribution in a commit, pull request, issue or comment. Remove the line."
UNREADABLE = "The command line cannot be read (an open quote). Write it again."
UNKNOWN_BRANCH = "The hook cannot tell which branch this push sends. Name the branch: git push origin <branch>."
HIDDEN_COMMAND = "A git alias, pager or helper set on the command line hides what runs. Run the command itself."

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
HIDDEN_COMMAND_CONFIG = re.compile(
    r"^(alias\.|pager\.|credential|url\.|remote\.|include|protocol\.|core\.(pager|sshcommand|editor|askpass|fsmonitor|hookspath|gitproxy)$"
    r"|sequence\.editor$|diff\.external$|gpg\.program$|.*\.(textconv|command|cmd|helper|program)$)",
    re.IGNORECASE,
)
HIDDEN_COMMAND_VARIABLES = {
    "GIT_PAGER", "GIT_EDITOR", "GIT_SSH", "GIT_SSH_COMMAND", "GIT_ASKPASS",
    "GIT_EXTERNAL_DIFF", "GIT_SEQUENCE_EDITOR", "GIT_EXEC_PATH", "GIT_PROXY_COMMAND",
}
PUSH_CONFIG_ALLOWED = re.compile(r"^(color|advice)\.", re.IGNORECASE)
EXPORTERS = {"export", "declare", "typeset", "local", "readonly"}
# The hook's own git, found before any command on the line can change PATH.
GIT = shutil.which("git") or "/usr/bin/git"
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


def split_words(words):
    """Splits words that hold a whole command, as env -S 'gh pr merge 3' does."""
    result = []
    for word in words:
        if not any(c.isspace() for c in word):
            result.append(word)
            continue
        if word.startswith("--") and "=" in word:
            word = word.split("=", 1)[1]
        elif word.startswith("-") and len(word) > 2:
            word = word[2:]
        try:
            result += shlex.split(word)
        except ValueError:
            result.append(word)
    return result


def with_assignments(assignments, words):
    assignments = dict(assignments)
    for word in words:
        if ASSIGNMENT.fullmatch(word):
            name, value = word.split("=", 1)
            assignments[name] = value
    return assignments


def candidates(words, assignments=None):
    """Every (command, variables set in front of it) that the words may run.

    A wrapper's options differ per wrapper and many take a value (sudo -u git), so the
    hook does not parse them. Each word after a wrapper that names a guarded program
    could be the command that runs, and so could the first word that is not an option;
    the hook checks them all."""
    assignments = dict(assignments or {})
    while words and ASSIGNMENT.fullmatch(words[0]):
        assignments = with_assignments(assignments, words[:1])
        words = words[1:]
    if not words or words[0].rsplit("/", 1)[-1] not in WRAPPERS:
        return [(words, assignments)]
    rest = split_words(words[1:])
    positions = {i for i, w in enumerate(rest) if w.rsplit("/", 1)[-1] in GUARDED | WRAPPERS}
    first = next((i for i, w in enumerate(rest) if not w.startswith("-") and not ASSIGNMENT.fullmatch(w)), None)
    if first is not None:
        positions.add(first)
    found = []
    for i in sorted(positions):
        found += candidates(rest[i:], with_assignments(assignments, rest[:i]))
    return found or [([], assignments)]


def git_subcommand(words):
    """Returns (directory from -C or None, repository options, config keys set with -c,
    subcommand, its arguments)."""
    directory = None
    repository_options = []
    config_keys = []
    i = 1
    while i < len(words):
        word = words[i]
        if word.startswith(("--git-dir=", "--work-tree=")):
            repository_options.append(word)
            i += 1
        elif word.startswith("--config-env="):
            config_keys.append(word.split("=", 2)[1])
            i += 1
        elif word in GIT_OPTIONS_WITH_VALUE or word == "--config-env":
            value = words[i + 1] if i + 1 < len(words) else ""
            if word == "-C":
                directory = value
            if word in {"--git-dir", "--work-tree"}:
                repository_options += [word, value]
            if word in {"-c", "--config-env"}:
                config_keys.append(value.split("=", 1)[0])
            i += 2
        elif word.startswith("-c") and len(word) > 2:
            config_keys.append(word[2:].split("=", 1)[0])
            i += 1
        elif word.startswith("-"):
            i += 1
        else:
            return directory, repository_options, config_keys, word, words[i + 1:]
    return directory, repository_options, config_keys, None, []


UNKNOWN = object()


def current_branch(directory, git_options, assignments):
    """The checked-out branch, None when HEAD is detached, UNKNOWN when git cannot tell.

    The command's own variables never reach this git: they could make the hook run
    code (PATH, LD_PRELOAD, GIT_CONFIG_*). Only GIT_DIR and GIT_WORK_TREE are taken,
    as options."""
    if "GIT_COMMON_DIR" in assignments:
        return UNKNOWN
    from_variables = []
    if "GIT_DIR" in assignments:
        from_variables += ["--git-dir", assignments["GIT_DIR"]]
    if "GIT_WORK_TREE" in assignments:
        from_variables += ["--work-tree", assignments["GIT_WORK_TREE"]]
    try:
        result = subprocess.run(
            [GIT, "-C", directory, *from_variables, *git_options,
             "symbolic-ref", "--short", "-q", "HEAD"],
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
    if name == "@":
        name = "HEAD"
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


def hides_a_command(config_keys, assignments, args):
    if any(HIDDEN_COMMAND_CONFIG.search(key) for key in config_keys):
        return True
    if any(name.startswith("GIT_CONFIG") or name in HIDDEN_COMMAND_VARIABLES for name in assignments):
        return True
    return any(a.startswith(("-O", "--open-files-in-pager", "--ext-diff")) for a in args)


def refusal_of_git(words, cwd, assignments):
    directory, repository_options, config_keys, subcommand, args = git_subcommand(words)
    if hides_a_command(config_keys, assignments, args):
        return HIDDEN_COMMAND
    if subcommand != "push":
        return None
    if any(not PUSH_CONFIG_ALLOWED.match(key) for key in config_keys):
        return HIDDEN_COMMAND
    if directory:
        cwd = os.path.join(cwd, directory)
    if any(a in {"--all", "--mirror"} for a in args):
        return PUSH_MAIN
    refspecs = push_targets(args)[1:]
    branches = [branch_name(r) for r in refspecs]
    if not refspecs or "head" in branches:
        branch = current_branch(cwd, repository_options, assignments)
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


def refusal_of_command(words, cwd, exported):
    for command, assignments in candidates(words, exported):
        message = refusal_of_candidate(command, assignments, cwd)
        if message:
            return message
    return None


def refusal_of_candidate(words, assignments, cwd):
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
        return refusal_of_git(words, cwd, assignments)
    if program in {"helm", "kubectl"}:
        return refusal_of_cluster(words)
    return None


def only_reads(commands):
    return all(
        reads_only(words, assignments)
        for command in commands
        for words, assignments in candidates(command)
    )


def reads_only(words, assignments):
    if not words or words[0] == "cd":
        return True
    program = words[0].rsplit("/", 1)[-1]
    if program in READ_ONLY_PROGRAMS:
        return not any(w.startswith("--pre") for w in words)
    if program == "git":
        _, _, config_keys, subcommand, args = git_subcommand(words)
        return subcommand in READ_ONLY_GIT and not hides_a_command(config_keys, assignments, args)
    return False


def refusal(line, cwd="."):
    try:
        commands = list(commands_of(line))
    except ValueError:
        return UNREADABLE
    if ATTRIBUTION_TEXT.search(line) and not only_reads(commands):
        return ATTRIBUTION
    exported = {}
    for words in commands:
        if words and words[0] == "cd" and len(words) > 1:
            cwd = os.path.join(cwd, os.path.expanduser(words[1]))
            continue
        if words and (words[0] in EXPORTERS or all(ASSIGNMENT.fullmatch(w) for w in words)):
            # export GIT_DIR=x; git push sets the variable for every later command.
            exported = with_assignments(exported, words)
            continue
        message = refusal_of_command(words, cwd, exported)
        if message:
            return message
    return None


def self_test():
    import tempfile

    with tempfile.TemporaryDirectory() as root:
        for branch in ("main", "fix-x"):
            repo = os.path.join(root, branch)
            subprocess.run(["git", "init", "-q", "-b", branch, repo], check=True)
        fake = os.path.join(root, "fake")
        os.mkdir(fake)
        with open(os.path.join(fake, "git"), "w") as planted:
            planted.write(f"#!/bin/sh\ntouch {root}/planted-git-ran\n")
        os.chmod(os.path.join(fake, "git"), 0o755)
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
            "sudo -u git git push origin main",
            "env curl -d 'Co-Authored-By: Claude' https://api.github.com/x",
            "env -S 'gh pr merge 3'",
            "env -S'gh pr merge 3'",
            "env --split-string='gh pr merge 3'",
            f"GIT_DIR={root}/main/.git git push",
            f"env GIT_DIR={root}/main/.git git push origin HEAD",
            f"cd {root}/main && git push origin @",
            "git -c core.pager='sh -c x' log --grep Co-Authored-By",
            "git -c alias.p='!git push origin main' p",
            "git --config-env=alias.p=X p",
            "GIT_SSH_COMMAND='sh -c x' git fetch",
            "git grep -Ocat Co-Authored-By",
            f"export GIT_DIR={root}/main/.git; git push",
            f"declare -x GIT_DIR={root}/main/.git && git push origin HEAD",
            f"GIT_DIR={root}/main/.git; export GIT_DIR; git push",
            "git -c remote.origin.push=HEAD:refs/heads/main push origin",
            "git -c url.x.insteadOf=y push origin fix/x",
            "git -c include.path=/tmp/x log --grep Co-Authored-By",
            f"GIT_COMMON_DIR={root}/main/.git git push",
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
            "env FOO=1 git status",
            "git -c color.ui=always log --grep Co-Authored-By",
            "git push origin @:fix/x",
            "git -c color.ui=always push origin fix/x",
            f"PATH={root}/fake:$PATH git push",
            f"LD_PRELOAD=x DYLD_INSERT_LIBRARIES=x git push",
        ]
        wrong = [c for c in blocked if refusal(c, root) is None]
        wrong += [c for c in allowed if refusal(c, os.path.join(root, "fix-x"))]
        if os.path.exists(os.path.join(root, "planted-git-ran")):
            wrong.append("the hook ran a git from the command's PATH")
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
