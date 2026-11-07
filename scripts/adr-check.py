#!/usr/bin/env python3
"""Checks that every ADR reference in the repository points at a record that exists. CI runs it in the diagrams job.

- `ADR 0046`, `ADR-0046`, `ADRs 0045 and 0046`, `ADR 0023, ADR 0035`: each number is exactly one file
  docs/adr/<category>/NNNN-<slug>.md, and no number is above the highest record.
- ADR file paths (`adr/operations/0033-ci-cd-and-supply-chain.md`, a relative link from an ADR) exist.
- Diagram names (`flow-0033-ci-cd.svg`, `sequence-0034-release-versioning.d2`) exist; a short `flow-0033` names
  exactly one diagram.
- A link to a part of a merged record (`#part-0045`) finds its `<a id="part-0045">`.

Not checked: the applied migrations (V*.sql keep their old numbers until PR 1b of the reset), the Postgres image's
build files (deploy/postgres: a change there needs a new image tag), the rendered SVGs
(the diagrams job compares them with their .d2), docs/adr/RENUMBER.md and the two ADR scripts, which name old numbers
on purpose. "was ADR 0045" names an old number on purpose too.

Usage: python3 scripts/adr-check.py [--self-test]
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import tempfile

MIGRATIONS = re.compile(r"^backend/libs/schema/src/main/resources/db/migration/V\d+__[^/]*\.sql$")
# The Postgres image's build files: a change there needs a new postgres.image.tag (the postgres-tag check), so a
# rebuild, a new digest and a signature. They keep their old numbers until the next real Postgres tag.
FROZEN = re.compile(r"^deploy/postgres/")
SKIPPED = {"docs/adr/RENUMBER.md", "scripts/adr-renumber.py", "scripts/adr-check.py"}
HAND_DRAWN_SVG = "docs/diagrams/hexagonal-architecture.svg"

NUM = r"\d{4}(?!\d)"
# One space, or a line break inside a comment ("ADR\n * 0032"): formatters wrap a reference anywhere.
SP = r"(?: |[ \t]*\n[ \t]*(?:\*|//|#|--)?[ \t]*)"
SEP = r"(?:,(?:" + SP + r"(?:and|or))?" + SP + r"|" + SP + r"(?:and|or)" + SP + r"| ?/ ?| & | \+ )"
WAS = r"(?P<was>\b(?:was|were) ADRs?" + SP + NUM + r")"
KEYWORD = r"(?P<kw>(?:(?<!\w)|(?<=\\n))ADRs?(?:-|" + SP + r")(?P<list>" + NUM + r"(?:" + SEP + r"(?:ADR" + SP + r")?" + NUM + r")*))"
PATH = r"(?<![\w-])(?P<path>(?:[\w.~%:-]+/)*(?P<pnum>\d{4})-[a-z0-9-]+\.md)(?P<anchor>#[\w-]+)?"
DIAGRAM = r"(?<![\w-])(?P<diagram>(?:flow|sequence)-\d{4}(?:-[a-z0-9-]*[a-z0-9])?)(?P<dext>\.(?:d2|svg))?(?![\w-])"
SELF_ANCHOR = r"\]\((?P<self>#part-\d{4})\)"
REFERENCE = re.compile("|".join([WAS, KEYWORD, PATH, DIAGRAM, SELF_ANCHOR]))


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
    if path in SKIPPED or MIGRATIONS.match(path) or FROZEN.match(path):
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
        self.anchors: dict[str, set[str]] = {}
        self.errors: list[str] = []

    def anchors_of(self, path: str) -> set[str]:
        if path not in self.anchors:
            try:
                text = open(os.path.join(self.root, path), encoding="utf-8").read()
            except OSError:
                text = ""
            self.anchors[path] = set(re.findall(r'<a id="([\w-]+)"></a>', text))
        return self.anchors[path]

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
        return self.errors

    def problems(self, m: re.Match, f: str) -> list[str]:
        if m.group("was"):
            return []
        if m.group("kw"):
            return [p for n in re.findall(NUM, m.group("list")) for p in self.number(n)]
        if m.group("path"):
            return self.path(m.group("path"), m.group("pnum"), m.group("anchor"), f)
        if m.group("diagram"):
            return self.diagram(m.group("diagram"))
        if m.group("self"):
            anchor = m.group("self")[1:]
            return [] if anchor in self.anchors_of(f) else [f'no <a id="{anchor}"></a> in this file']
        return []

    def number(self, n: str) -> list[str]:
        if n > self.highest:
            return [f"ADR {n} is above the highest record, {self.highest} (an old number? see docs/adr/RENUMBER.md)"]
        found = self.records.get(n, [])
        if len(found) != 1:
            return [f"ADR {n} is {len(found)} files in docs/adr, not one"]
        return []

    def path(self, path: str, num: str, anchor: str | None, f: str) -> list[str]:
        target = None
        local = os.path.normpath(os.path.join(os.path.dirname(f), path)).replace(os.sep, "/")
        if local in self.records.get(num, []):
            target = local
        elif "adr/" in path:
            tail = "docs/" + path[path.rindex("adr/") :]
            target = tail if tail in self.records.get(num, []) else None
        elif "/" not in path and not f.endswith(".md"):  # a file name in code; in Markdown it is a relative link
            named = [r for r in self.records.get(num, []) if r.endswith("/" + path)]
            target = named[0] if len(named) == 1 else None
        if target is None:
            if num not in self.records and "adr/" not in path and not f.startswith("docs/adr/"):
                return []  # a file named NNNN-x.md that is not an ADR
            return [f"{path} is not an ADR file"]
        if anchor and anchor.startswith("#part-") and anchor[1:] not in self.anchors_of(target):
            return [f'no <a id="{anchor[1:]}"></a> in {target}']
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
        "docs/adr/search/0002-ranking.md": '# 0002 — Ranking\n\n<a id="part-0009"></a>\n\n## Part: was ADR 0009 — R\n'
        "See [0001](../foundation/0001-stack.md) and [the part that was ADR 0009](#part-0009).\n",
        "docs/diagrams/flow-0001-stack.d2": "# flow\n",
        "docs/diagrams/flow-0002-ranking.d2": "# flow\n",
        "docs/diagrams/flow-0002-fusion.d2": "# flow\n",
        "README.md": "ADR 0001, ADRs 0001 and 0002, ADR 0001/0002, ADR-0002, ADR 0001, ADR 0002 §3.\n"
        " * wrapped by a formatter: ADR\n * 0002, ADR 0001 and\n * 0002\n"
        'label: "a box\\nADR 0002"\n'
        "[ranking](docs/adr/search/0002-ranking.md#part-0009) · flow-0001 · flow-0002-ranking.svg\n"
        "sha256:0000111122223333 port 8081 version 2024 0099-notes.md\n",
        "backend/libs/schema/src/main/resources/db/migration/V1__schema.sql": "-- ADR 0099 stays until 1b\n",
        "deploy/postgres/Dockerfile": "# ADR 0099 stays until the next Postgres tag\n",
        "docs/adr/RENUMBER.md": "| 0099 | ADR 0099 |\n",
    }
    cases = {
        "clean tree": ({}, 0),
        "unknown number": ({"a.java": "// ADR 0003\n"}, 1),
        "unknown number on the next line": ({"a.java": "/** the guide (ADR\n * 0042). */\n"}, 1),
        "unknown number in a d2 label": ({"a.d2": 'x: "IndexAcceptedCandidate\\nADR 0051"\n'}, 1),
        "above the highest": ({"a.kt": "// ADRs 0001 and 0071\n"}, 1),
        "old ADR path": ({"a.md": "[x](docs/adr/search/0009-ranking.md)\n"}, 1),
        "relative link to a moved record": ({"docs/adr/foundation/0003-x.md": "[0002](0002-ranking.md)\n"}, 1),
        "missing diagram": ({"docs/diagrams/README.md": "[flow](flow-0003-gone.svg)\n"}, 1),
        "short diagram name with two diagrams": ({"a.d2": "# see flow-0002\n"}, 1),
        "missing part anchor": ({"a.md": "[0002](docs/adr/search/0002-ranking.md#part-0010)\n"}, 1),
        "missing own anchor": ({"docs/adr/foundation/0001-stack.md": "# 0001\n[p](#part-0005)\n"}, 1),
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
        sys.exit(f"adr-check: {len(errors)} ADR reference(s) do not resolve (docs/adr/RENUMBER.md maps the old numbers)")
    print("adr-check: every ADR reference resolves")


if __name__ == "__main__":
    main()
