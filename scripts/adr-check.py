#!/usr/bin/env python3

from __future__ import annotations

import os
import re
import subprocess
import sys
import tempfile

SKIPPED = {"scripts/adr-check.py"}
HISTORY_WORDS = ("was 00", "merged:", "previously", "superseded", "replaces")
HAND_DRAWN_SVG = "docs/diagrams/hexagonal-architecture.svg"

NUM = r"\d{4}(?!\d)"
SP = r"(?: |[ \t]*\n[ \t]*(?:\*|//|#|--)?[ \t]*)"
SEP = r"(?:,(?:" + SP + r"(?:and|or))?" + SP + r"|" + SP + r"(?:and|or)" + SP + r"| ?/ ?| & | \+ )"
KEYWORD = r"(?P<kw>(?:(?<!\w)|(?<=\\n))ADRs?(?:-|" + SP + r")(?P<list>" + NUM + r"(?:" + SEP + r"(?:ADR" + SP + r")?" + NUM + r")*))"
PATH = r"(?<![\w-])(?P<path>(?:[\w.~%:-]+/)*(?P<pnum>\d{4})-[a-z0-9-]+\.md)"
DIAGRAM = r"(?<![\w-])(?P<diagram>(?:flow|sequence)-\d{4}(?:-[a-z0-9-]*[a-z0-9])?)(?P<dext>\.(?:d2|svg))?(?![\w-])"
REFERENCE = re.compile("|".join([KEYWORD, PATH, DIAGRAM]))


def files_of(root: str) -> list[str]:
    try:
        out = subprocess.run(["git", "-C", root, "ls-files", "-z"], capture_output=True, check=True).stdout
        names = [f for f in out.decode().split("\0") if f]
    except (subprocess.CalledProcessError, FileNotFoundError):
        names = [
            os.path.relpath(os.path.join(d, f), root).replace(os.sep, "/")
            for d, _dirs, fs in os.walk(root)
            if "/.git" not in d
            for f in fs
        ]
    return [f for f in names if os.path.isfile(os.path.join(root, f)) and not os.path.islink(os.path.join(root, f))]


def checked(path: str) -> bool:
    if path in SKIPPED:
        return False
    return not path.endswith(".svg") or path == HAND_DRAWN_SVG


class Check:
    def __init__(self, root: str):
        self.root = root
        files = files_of(root)
        self.files = files
        self.records: dict[str, list[str]] = {}
        for f in files:
            m = re.fullmatch(r"docs/adr/[a-z]+/(\d{4})-[a-z0-9-]+\.md", f)
            if m:
                self.records.setdefault(m.group(1), []).append(f)
        self.highest = max(self.records, default="0000")
        self.diagrams = {f[len("docs/diagrams/") : -3] for f in files if re.fullmatch(r"docs/diagrams/[^/]+\.d2", f)}
        self.errors: list[str] = []

    def run(self) -> list[str]:
        for f in self.files:
            if not checked(f):
                continue
            try:
                text = open(os.path.join(self.root, f), encoding="utf-8").read()
            except UnicodeDecodeError:
                continue
            for m in REFERENCE.finditer(text):
                line = text.count("\n", 0, m.start()) + 1
                for problem in self.problems(m, f):
                    self.errors.append(f"{f}:{line}: {m.group(0)}: {problem}")
            if f.startswith("docs/adr/") and f.endswith(".md"):
                self.errors.extend(self.history(f, text))
        return self.errors

    def history(self, f: str, text: str) -> list[str]:
        return [
            f'{f}:{number}: "{word}": an ADR states the decision in force, not its history'
            for number, line in enumerate(text.splitlines(), 1)
            for word in HISTORY_WORDS
            if word in line
        ]

    def problems(self, m: re.Match, f: str) -> list[str]:
        if m.group("kw"):
            return [p for n in re.findall(NUM, m.group("list")) for p in self.number(n)]
        if m.group("path"):
            return self.path(m.group("path"), m.group("pnum"), f)
        return self.diagram(m.group("diagram"))

    def number(self, n: str) -> list[str]:
        if n > self.highest:
            return [f"ADR {n} is above the highest record, {self.highest}"]
        found = self.records.get(n, [])
        if len(found) != 1:
            return [f"ADR {n} is {len(found)} files in docs/adr, not one"]
        return []

    def path(self, path: str, num: str, f: str) -> list[str]:
        target = None
        local = os.path.normpath(os.path.join(os.path.dirname(f), path)).replace(os.sep, "/")
        if local in self.records.get(num, []):
            target = local
        elif "adr/" in path:
            tail = "docs/" + path[path.rindex("adr/") :]
            target = tail if tail in self.records.get(num, []) else None
        elif "/" not in path and not f.endswith(".md"):
            named = [r for r in self.records.get(num, []) if r.endswith("/" + path)]
            target = named[0] if len(named) == 1 else None
        if target is None:
            if num not in self.records and "adr/" not in path and not f.startswith("docs/adr/"):
                return []
            return [f"{path} is not an ADR file"]
        return []

    def diagram(self, name: str) -> list[str]:
        if name in self.diagrams:
            return []
        if re.fullmatch(r"(?:flow|sequence)-\d{4}", name):
            found = [d for d in self.diagrams if d.startswith(name + "-")]
            if len(found) == 1:
                return []
            return [f"{name} names {len(found)} diagrams; write the full name"]
        return [f"docs/diagrams/{name}.d2 does not exist"]


def self_test() -> None:
    def tree(files: dict[str, str]) -> str:
        root = tempfile.mkdtemp(prefix="adr-check-")
        for path, text in files.items():
            os.makedirs(os.path.join(root, os.path.dirname(path)), exist_ok=True)
            with open(os.path.join(root, path), "w", encoding="utf-8") as out:
                out.write(text)
        return root

    base = {
        "docs/adr/foundation/0001-stack.md": "# 0001 — Stack\n",
        "docs/adr/search/0002-ranking.md": "# 0002 — Ranking\n\nSee [0001](../foundation/0001-stack.md).\n"
        "The review status `SUPERSEDED` is a value in the code.\n",
        "docs/diagrams/flow-0001-stack.d2": "# flow\n",
        "docs/diagrams/flow-0002-ranking.d2": "# flow\n",
        "docs/diagrams/flow-0002-fusion.d2": "# flow\n",
        "README.md": "ADR 0001, ADRs 0001 and 0002, ADR 0001/0002, ADR-0002, ADR 0001, ADR 0002 §3.\n"
        " * wrapped by a formatter: ADR\n * 0002, ADR 0001 and\n * 0002\n"
        'label: "a box\\nADR 0002"\n'
        "[ranking](docs/adr/search/0002-ranking.md#decision) · flow-0001 · flow-0002-ranking.svg\n"
        "previously, outside docs/adr, is a word like any other\n"
        "sha256:0000111122223333 port 8081 version 2024 0099-notes.md\n",
    }
    cases = {
        "clean tree": ({}, 0),
        "unknown number": ({"a.java": "// ADR 0003\n"}, 1),
        "unknown number on the next line": ({"a.java": "/** the guide (ADR\n * 0042). */\n"}, 1),
        "unknown number in a d2 label": ({"a.d2": 'x: "IndexAcceptedCandidate\\nADR 0051"\n'}, 1),
        "unknown number in the schema migration": (
            {"backend/libs/schema/src/main/resources/db/migration/V1__schema.sql": "-- ADR 0099\n"},
            1,
        ),
        "above the highest": ({"a.kt": "// ADRs 0001 and 0071\n"}, 1),
        "old ADR path": ({"a.md": "[x](docs/adr/search/0009-ranking.md)\n"}, 1),
        "relative link to a moved record": ({"docs/adr/foundation/0003-x.md": "[0002](0002-ranking.md)\n"}, 1),
        "missing diagram": ({"docs/diagrams/README.md": "[flow](flow-0003-gone.svg)\n"}, 1),
        "short diagram name with two diagrams": ({"a.d2": "# see flow-0002\n"}, 1),
        "unknown number in the Postgres image": ({"deploy/postgres/Dockerfile": "# ADR 0099\n"}, 1),
        "a history word in an ADR": ({"docs/adr/foundation/0003-x.md": "# 0003\nIt replaces the old index.\n"}, 1),
        "two history words in the ADR index": ({"docs/adr/README.md": "0003 (was 0007), merged: 0004\n"}, 2),
        "a history word on each of two lines": (
            {"docs/adr/search/0002-ranking.md": "# 0002\nIt was previously BM25.\nADR 0001 is superseded.\n"},
            2,
        ),
        "two files for one number": (
            {"docs/adr/edge/0003-a.md": "# 0003\n", "docs/adr/quality/0003-b.md": "# 0003\n", "a.ts": "// ADR 0003\n"},
            1,
        ),
    }
    failed = 0
    for name, (extra, want) in cases.items():
        errors = Check(tree({**base, **extra})).run()
        ok = len(errors) == want
        failed += not ok
        print(f"{'ok  ' if ok else 'FAIL'} {name}: {len(errors)} error(s), want {want}" + ("" if ok else f" {errors}"))
    if failed:
        sys.exit(f"adr-check self-test: {failed} case(s) failed")
    print("adr-check self-test: all cases pass")


def main() -> None:
    if "--self-test" in sys.argv[1:]:
        self_test()
        return
    root = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True)
    errors = Check(root.stdout.strip()).run()
    for e in errors:
        print(e)
    if errors:
        sys.exit(f"adr-check: {len(errors)} problem(s): an ADR reference that does not resolve, or a history word in docs/adr")
    print("adr-check: every ADR reference resolves and no ADR tells its history")


if __name__ == "__main__":
    main()
