#!/usr/bin/env python3
"""Moves every ADR to its number of the first release, as docs/adr/RENUMBER.md maps it. Runs once.

One pass over every tracked text file: each reference is looked up in the mapping once, so an old number that is also
a new one (old 0008 is new 0009, old 0009 is new 0012) never moves twice. It rewrites only explicit forms:

- the ADR keyword with its numbers (`ADR 0046`, `ADR-0046`, `ADRs 0045 and 0046`, `ADR 0023/0035`), and a section
  after it (`§7`, `decision 7`, `point 8`, `section 2`);
- ADR file paths (`adr/operations/0046-supply-chain.md`; a relative link is computed from the file's own new place);
- the text of a Markdown link to an ADR or a diagram (`[0046](...)`, `[flow 0046](...)`);
- diagram names (`flow-0046-supply-chain.svg`, `sequence-0048-....d2`, the short `flow-0017`);
- bare numbers, but only in the ADRs themselves and in the .d2 sources, where every four-digit number from 0001 to
  0071 is an ADR.

It never touches the applied migrations (V*.sql): their Flyway checksums would change. PR 1b replaces them. Nor the
Postgres image's build files (deploy/postgres): a change there needs a new image tag, a rebuild and a signature.

A merged record becomes one file: a short header, then each old record under `## Part: was ADR NNNN — <title>`,
its headings one level down. Both README indexes are written again from the new files and the old rows.

Usage, from the repository root: python3 scripts/adr-renumber.py [--dry-run]. It prints what it did.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
from dataclasses import dataclass, field

MAPPING = "docs/adr/RENUMBER.md"
ADR_DIR = "docs/adr"
DIAGRAMS = "docs/diagrams"
ADR_README = "docs/adr/README.md"
DIAGRAMS_README = "docs/diagrams/README.md"
MIGRATIONS = re.compile(r"^backend/libs/schema/src/main/resources/db/migration/V\d+__[^/]*\.sql$")
# The Postgres image's build files: a change there needs a new postgres.image.tag (the postgres-tag check), so a
# rebuild, a new digest and a signature. They keep their old numbers until the next real Postgres tag.
FROZEN = re.compile(r"^deploy/postgres/")
NOT_REWRITTEN = {MAPPING, "scripts/adr-renumber.py", "scripts/adr-check.py"}
# The one SVG drawn by hand. Every other SVG is rendered from its .d2 by docs/diagrams/render.sh.
HAND_DRAWN_SVG = "docs/diagrams/hexagonal-architecture.svg"
CATEGORY_ORDER = ["foundation", "search", "guide", "scout", "edge", "frontend", "operations", "quality"]


# ---------------------------------------------------------------------------------------------------------------
# The mapping


@dataclass
class Old:
    num: str
    path: str
    new: str


@dataclass
class New:
    num: str
    cat: str
    file: str
    members: list[str]
    title: str | None = None
    pr: str | None = None

    @property
    def path(self) -> str:
        return f"{ADR_DIR}/{self.cat}/{self.file}"

    @property
    def merged(self) -> bool:
        return len(self.members) > 1


def table_rows(text: str, heading: str) -> list[list[str]]:
    section = text.split(f"\n## {heading}\n", 1)[1].split("\n## ", 1)[0]
    return [
        [c.strip().strip("`") for c in line.strip().strip("|").split("|")]
        for line in section.splitlines()
        if line.startswith("| 0")
    ]


def read_mapping() -> tuple[dict[str, Old], dict[str, New]]:
    text = open(MAPPING, encoding="utf-8").read()
    olds: dict[str, Old] = {}
    news: dict[str, New] = {}
    for num, old_path, new, cat, file, _merged_with, _note in table_rows(text, "Old to new"):
        if num in olds:
            sys.exit(f"{MAPPING}: old {num} appears twice")
        olds[num] = Old(num, old_path, new)
        entry = news.setdefault(new, New(new, cat, file, []))
        if (entry.cat, entry.file) != (cat, file):
            sys.exit(f"{MAPPING}: new {new} has two files")
        entry.members.append(num)
    for new, cat, file, title, merges, pr in table_rows(text, "New records"):
        entry = news[new]
        if (entry.cat, entry.file) != (cat, file):
            sys.exit(f"{MAPPING}: new {new} differs between the two tables")
        members = [m.strip() for m in merges.split(",")]
        if sorted(members) != sorted(entry.members):
            sys.exit(f"{MAPPING}: new {new} merges {members}, the old table says {entry.members}")
        entry.members, entry.title, entry.pr = members, title, pr
    for entry in news.values():
        if entry.merged and entry.title is None:
            sys.exit(f"{MAPPING}: merged {entry.num} has no row in 'New records'")
    if len({e.path for e in news.values()}) != len(news):
        sys.exit(f"{MAPPING}: two new records share a path")
    return olds, news


# ---------------------------------------------------------------------------------------------------------------
# What the tree holds


def tracked_files() -> list[str]:
    out = subprocess.run(["git", "ls-files", "-z"], capture_output=True, check=True).stdout.decode()
    return [f for f in out.split("\0") if f and os.path.isfile(f) and not os.path.islink(f)]


def refuse_if_done(olds: dict[str, Old], news: dict[str, New]) -> None:
    missing = [o.path for o in olds.values() if not os.path.isfile(o.path)]
    if missing:
        sys.exit(
            "adr-renumber: this tree is already renumbered (or is not the base of 1a): "
            f"{len(missing)} old ADR files are missing, e.g. {missing[0]}. Run it on a fresh copy of the base."
        )
    for entry in news.values():
        if entry.merged and os.path.exists(entry.path):
            sys.exit(f"adr-renumber: {entry.path} exists already: this tree is renumbered")


def diagram_files() -> dict[tuple[str, str], str]:
    """(kind, old number) -> slug, for every flow-NNNN-slug.d2 and sequence-NNNN-slug.d2."""
    found: dict[tuple[str, str], str] = {}
    for name in sorted(os.listdir(DIAGRAMS)):
        m = re.fullmatch(r"(flow|sequence)-(0\d{3})-([a-z0-9-]+)\.d2", name)
        if m:
            key = (m.group(1), m.group(2))
            if key in found:
                sys.exit(f"two {key[0]} diagrams for {key[1]}: the short form {key[0]}-{key[1]} would be ambiguous")
            found[key] = m.group(3)
    return found


# ---------------------------------------------------------------------------------------------------------------
# The rewrite

NUM = r"0\d{3}(?!\d)"
# One space, or a line break inside a comment ("ADR\n * 0069"): formatters wrap a reference anywhere.
SP = r"(?: |[ \t]*\n[ \t]*(?:\*|//|#|--)?[ \t]*)"
SEP = r"(?:,(?:" + SP + r"(?:and|or))?" + SP + r"|" + SP + r"(?:and|or)" + SP + r"| ?/ ?| & | \+ )"
# A section of a record: "§7", "decision 7", "point 8", "section 2", or a plain ", 7" ("ADR 0040, 6").
QUAL = (
    r"(?:,?" + SP + r"(?:decisions?|points?|sections?) \d+(?:\.\d+)*|,?" + SP + r"?§ ?\d+(?:\.\d+)*"
    r"|, \d{1,2}(?![\w.]))"
)
LINK = r"(?P<link>!?\[(?P<ltext>[^\]\n]*)\]\((?P<ltarget>[^)\s]+)\))(?P<lqual>" + QUAL + r")?"
WAS = r"(?P<was>\b(?:was|were) ADRs?" + SP + NUM + r")"
KEYWORD = (
    r"(?P<kw>(?:(?<!\w)|(?<=\\n))ADRs?)(?P<kwsep>-|" + SP + r")(?P<klist>" + NUM + r"(?:" + SEP + r"(?:ADR" + SP + r")?" + NUM + r")*)"
    r"(?P<kqual>" + QUAL + r")?"
)
PATH = r"(?<![\w-])(?P<pprefix>(?:[\w.~%:-]+/)*)(?P<pnum>" + NUM + r")-(?P<pslug>[a-z0-9-]+)\.md"
DIAGRAM = (
    r"(?<![\w-])(?P<dkind>flow|sequence)-(?P<dnum>" + NUM + r")(?:-(?P<dslug>[a-z0-9-]*[a-z0-9]))?"
    r"(?P<dext>\.(?:d2|svg))?(?![\w-])"
)
BARE = r"(?<![\w./#:§-])(?P<blist>" + NUM + r"(?:" + SEP + NUM + r")*)(?P<bqual>" + QUAL + r")?(?![\w])"

EXPLICIT = re.compile("|".join([LINK, WAS, KEYWORD, PATH, DIAGRAM]))
WITH_BARE = re.compile("|".join([LINK, WAS, KEYWORD, PATH, DIAGRAM, BARE]))
SPLIT_LIST = re.compile(r"(" + SEP + r")")
ADR_LINK = re.compile(r"(?P<p>(?:[\w.~%:-]+/)*)(?P<n>" + NUM + r")-(?P<s>[a-z0-9-]+)\.md")


@dataclass
class Context:
    """Where a text is: the folder it comes from, the folder it lands in, and the merged group it is part of."""

    path: str
    old_dir: str
    new_dir: str
    bare: bool = False
    group: list[str] | None = None
    label: bool = False  # the text of a Markdown link

    def plain(self, bare: bool) -> Context:
        return Context(self.path, self.old_dir, self.new_dir, bare=bare, label=True)


@dataclass
class Report:
    counts: dict[str, int] = field(default_factory=dict)
    sections: list[str] = field(default_factory=list)
    bare: list[str] = field(default_factory=list)
    wrapped: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)

    def count(self, form: str) -> None:
        self.counts[form] = self.counts.get(form, 0) + 1


class Renumber:
    def __init__(self, olds: dict[str, Old], news: dict[str, New], diagrams: dict[tuple[str, str], str]):
        self.olds, self.news, self.diagrams = olds, news, diagrams
        self.report = Report()

    def new_of(self, old: str) -> str:
        return self.olds[old].new

    def merged(self, old: str) -> bool:
        return self.news[self.new_of(old)].merged

    @staticmethod
    def own_part(old: str) -> str:
        return f"the part that was ADR {old}"

    @staticmethod
    def is_self(old: str, ctx: Context) -> bool:
        return ctx.group is not None and old in ctx.group

    # -- lists of numbers --------------------------------------------------------------------------------------

    def render_list(self, keyword: str, kwsep: str, raw: str, qual: str, ctx: Context) -> str | None:
        """Old numbers, with or without the ADR keyword: each mapped once, a repeat dropped with its separator."""
        tokens = SPLIT_LIST.split(raw)
        items, seps = tokens[0::2], tokens[1::2]
        repeat = {i: item[:-4] for i, item in enumerate(items)}  # "ADR " when the keyword comes again
        items = [item[-4:] if item.startswith("ADR") else item for item in items]
        valid = 0
        while valid < len(items) and items[valid] in self.olds:
            valid += 1
        if valid == 0:
            return None
        rest = ""
        if valid < len(items):
            rest = "".join(tokens[2 * valid - 1 :]) + qual  # tokens still hold the raw items
            qual = ""  # the section belongs to a number this script does not know
        items, seps = items[:valid], seps[: valid - 1]
        last = items[-1]

        kept: list[str] = []
        kept_seps: list[str] = []
        selves: list[str] = []
        for i, old in enumerate(items):
            if self.is_self(old, ctx):
                selves.append(old)
                continue
            new = self.new_of(old)
            if new in [k[-4:] for k in kept]:
                continue
            if kept:
                kept_seps.append(seps[i - 1])
            kept.append(repeat[i] + new if i > 0 else new)

        if qual and not self.is_self(last, ctx) and self.merged(last):
            idx = [k[-4:] for k in kept].index(self.new_of(last))
            kept[idx] = f"{kept[idx]} (part was {last})"

        text = ""
        if kept:
            body = kept[0] + "".join(s + n for s, n in zip(kept_seps, kept[1:]))
            if keyword:
                kw = "ADR" if keyword == "ADRs" and len(kept) == 1 else keyword
                text = kw + kwsep + body
            else:
                text = body
        for old in selves:
            text = (text + " and " if text else "") + self.own_part(old)
        return text + qual + rest

    # -- paths -------------------------------------------------------------------------------------------------

    def adr_path(self, prefix: str, num: str, slug: str, ctx: Context, link: bool) -> str | None:
        old = self.olds.get(num)
        if old is None:
            return None
        new = self.news[old.new]
        old_dir = os.path.dirname(old.path)
        if f"{num}-{slug}.md" != os.path.basename(old.path):
            self.report.notes.append(f"{ctx.path}: {prefix}{num}-{slug}.md was not a file; now {new.path}")
        resolved = os.path.normpath(os.path.join(ctx.old_dir, prefix + f"{num}-{slug}.md"))
        if os.path.dirname(resolved) == old_dir:
            return os.path.relpath(new.path, ctx.new_dir)
        old_cat = old_dir.rsplit("/", 1)[1]
        if prefix.endswith(f"adr/{old_cat}/"):
            return prefix[: -len(old_cat) - 1] + f"{new.cat}/{new.file}"
        if prefix and link:
            self.report.notes.append(f"{ctx.path}: link {prefix}{num}-{slug}.md does not resolve; kept its prefix")
        return prefix + new.file

    def diagram_name(self, kind: str, num: str, slug: str | None, ext: str | None, ctx: Context) -> str | None:
        own = self.diagrams.get((kind, num))
        if own is None or num not in self.olds:
            return None
        if slug is not None and slug != own:
            self.report.notes.append(f"{ctx.path}: {kind}-{num}-{slug} is not a diagram; left as it is")
            return None
        return f"{kind}-{self.new_of(num)}-{own}" + (ext or "")

    # -- one pass ----------------------------------------------------------------------------------------------

    def rewrite(self, text: str, ctx: Context, line_offset: int = 0) -> str:
        pattern = WITH_BARE if ctx.bare else EXPLICIT

        def replace(m: re.Match) -> str:
            before = m.group(0)
            after = self.replace_one(m, ctx)
            if after is None or after == before:
                return before
            line = text.count("\n", 0, m.start()) + 1 + line_offset
            qual = m.group("lqual") or m.group("kqual") or (m.group("bqual") if ctx.bare else None)
            if qual:
                self.report.sections.append(f"{ctx.path}:{line}: {before} -> {after}")
            if "\n" in before:
                self.report.wrapped.append(f"{ctx.path}:{line}: {before!r} -> {after!r}")
            if ctx.bare and m.group("blist") and not ctx.label:
                self.report.bare.append(f"{ctx.path}:{line}: {before} -> {after}")
            return after

        return pattern.sub(replace, text)

    def replace_one(self, m: re.Match, ctx: Context) -> str | None:
        if m.group("link"):
            out = self.link(m, ctx)
            if out != m.group(0):
                self.report.count("Markdown link to an ADR or a diagram")
            return out
        if m.group("was"):
            return None
        if m.group("kw"):
            out = self.render_list(m.group("kw"), m.group("kwsep"), m.group("klist"), m.group("kqual") or "", ctx)
            if out is not None:
                self.report.count("ADR keyword with its numbers")
            return out
        if m.group("pnum"):
            out = self.adr_path(m.group("pprefix"), m.group("pnum"), m.group("pslug"), ctx, link=False)
            if out is not None:
                self.report.count("ADR path outside a link")
            return out
        if m.group("dkind"):
            out = self.diagram_name(m.group("dkind"), m.group("dnum"), m.group("dslug"), m.group("dext"), ctx)
            if out is not None:
                self.report.count("diagram name outside a link")
            return out
        if ctx.bare and m.group("blist"):
            out = self.render_list("", "", m.group("blist"), m.group("bqual") or "", ctx)
            if out is not None:
                self.report.count("number in a link text" if ctx.label else "bare number in an ADR or a .d2")
            return out
        return None

    def link(self, m: re.Match, ctx: Context) -> str:
        text, target, qual = m.group("ltext"), m.group("ltarget"), m.group("lqual") or ""
        bang = "!" if m.group("link").startswith("!") else ""
        anchor = ""
        if "#" in target:
            target, anchor = target.split("#", 1)
            anchor = "#" + anchor
        pm = ADR_LINK.fullmatch(target)
        if pm and pm.group("n") in self.olds:
            old = pm.group("n")
            numeric = re.fullmatch(r"(?:ADR )?" + old, text) is not None
            if self.is_self(old, ctx):
                label = self.own_part(old) if numeric else self.rewrite(text, ctx.plain(bare=True))
                return f"{bang}[{label}](#part-{old}){qual}"
            new_target = self.adr_path(pm.group("p"), old, pm.group("s"), ctx, link=True)
            if qual and numeric and self.merged(old):
                label = text.replace(old, f"{self.new_of(old)} (part was {old})")
            else:
                label = self.rewrite(text, ctx.plain(bare=True))
            return f"{bang}[{label}]({new_target}{anchor}){qual}"
        if re.search(r"(?:^|/)(?:flow|sequence)-" + NUM, target):
            label = self.rewrite(text, ctx.plain(bare=True))
            return f"{bang}[{label}]({self.rewrite(target, ctx.plain(bare=False))}{anchor}){qual}"
        label = self.rewrite(text, ctx)
        return f"{bang}[{label}]({target}{anchor}){self.rewrite(qual, ctx) if qual else ''}"


# ---------------------------------------------------------------------------------------------------------------
# Building the new tree


def split_title(text: str) -> tuple[str, str, str]:
    """'# 0033 — Title\\nrest' -> ('0033', 'Title', '\\nrest')."""
    first, rest = text.split("\n", 1)
    m = re.fullmatch(r"# (0\d{3}) — (.+)", first)
    if not m:
        sys.exit(f"unexpected first line: {first!r}")
    return m.group(1), m.group(2), "\n" + rest


REPEATED_LINK = re.compile(r"(\[[^\]\n]+\]\([^)\s]+\))(?:,\s+\1)+")


def without_repeats(text: str) -> str:
    """'[0028](a.md), [0028](a.md)' -> '[0028](a.md)': two old records that are now one."""
    return REPEATED_LINK.sub(r"\1", text)


def demote(text: str) -> str:
    out, fence = [], False
    for line in text.split("\n"):
        if line.startswith("```") or line.startswith("~~~"):
            fence = not fence
        elif not fence and re.match(r"#{1,5} ", line):
            line = "#" + line
        out.append(line)
    return "\n".join(out)


def builds_on(text: str) -> list[str]:
    m = re.search(r"\*\*Builds on:\*\*(.*?)(?:\*\*[A-Z][a-z]+:\*\*|\n\n)", text, re.S)
    return re.findall(r"\[(0\d{3})\]\(", m.group(1)) if m else []


def old_rows(path: str) -> dict[str, list[str]]:
    rows = {}
    for line in open(path, encoding="utf-8").read().splitlines():
        m = re.match(r"\| \[(0\d{3})\]\(", line)
        if m:
            rows[m.group(1)] = [c.strip() for c in line.strip().strip("|").split("|")]
    return rows


class Tree:
    def __init__(self, r: Renumber):
        self.r = r
        self.writes: dict[str, str] = {}
        self.removes: set[str] = set()

    def adr_context(self, old: str, group: list[str] | None) -> Context:
        path = self.r.olds[old].path
        new = self.r.news[self.r.new_of(old)]
        return Context(path, os.path.dirname(path), os.path.dirname(new.path), bare=True, group=group)

    def read_old(self, old: str) -> tuple[str, str]:
        _num, title, body = split_title(open(self.r.olds[old].path, encoding="utf-8").read())
        return title, body

    def build_adrs(self) -> None:
        r = self.r
        for new in r.news.values():
            self.removes |= {r.olds[old].path for old in new.members}
            if not new.merged:
                title, body = self.read_old(new.members[0])
                body = r.rewrite(body, self.adr_context(new.members[0], None))
                self.writes[new.path] = without_repeats(f"# {new.num} — {title}" + body)
                continue
            parts = [
                f"# {new.num} — {new.title}\n\n**Status.** Accepted. Merges old {', '.join(new.members)} "
                f"(texts merged in PR {new.pr} of the reset). Until then each part below is the old record as it "
                "was, with its numbers and links moved to the new ones.\n"
            ]
            for old in new.members:
                title, body = self.read_old(old)
                body = r.rewrite(demote(body), self.adr_context(old, new.members)).strip("\n")
                parts.append(f'\n<a id="part-{old}"></a>\n\n## Part: was ADR {old} — {title}\n\n{body}\n')
            self.writes[new.path] = without_repeats("".join(parts))

    def build_diagrams(self) -> None:
        r = self.r
        for (kind, old), slug in r.diagrams.items():
            for ext in ("d2", "svg"):
                src = f"{DIAGRAMS}/{kind}-{old}-{slug}.{ext}"
                dst = f"{DIAGRAMS}/{kind}-{r.new_of(old)}-{slug}.{ext}"
                if not os.path.isfile(src):
                    continue
                text = open(src, encoding="utf-8", newline="").read()
                if ext == "d2":
                    text = r.rewrite(text, Context(src, DIAGRAMS, DIAGRAMS, bare=True))
                self.removes.add(src)
                if dst in self.writes:
                    sys.exit(f"two diagrams land on {dst}")
                self.writes[dst] = text

    def build_adr_readme(self) -> None:
        r = self.r
        text = open(ADR_README, encoding="utf-8").read()
        rows = old_rows(ADR_README)
        head = text.split("\n## ", 1)[0]
        footer = "## Writing a new ADR" + text.split("## Writing a new ADR", 1)[1]
        headings = {m.group(2): m.group(0) for m in re.finditer(r"^## (.+) — \[`(\w+)/`\]\(\2/\)$", text, re.M)}
        plain = Context(ADR_README, ADR_DIR, ADR_DIR)
        head = r.rewrite(head, plain).replace(" The number 0021 is not used.", "").rstrip("\n")
        head += (
            "\n\nThe numbers are those of the first release: [RENUMBER.md](RENUMBER.md) maps every old number to its"
            " new one.\nA merged record holds its old records as parts until its pull request of the reset writes"
            " the merged text.\n"
        )
        out = [head]
        for cat in CATEGORY_ORDER:
            out.append(f"\n{headings[cat]}\n\n| # | Decision | Builds on | Diagrams |\n|---|---|---|---|\n")
            for new in sorted((n for n in r.news.values() if n.cat == cat), key=lambda n: n.num):
                out.append(self.adr_row(new, rows) + "\n")
        out.append("\n" + r.rewrite(footer, plain))
        self.writes[ADR_README] = "".join(out)

    def adr_row(self, new: New, rows: dict[str, list[str]]) -> str:
        r = self.r
        ctx = Context(ADR_README, ADR_DIR, ADR_DIR, bare=True, group=new.members if new.merged else None)
        explicit = Context(ADR_README, ADR_DIR, ADR_DIR)
        decisions = [f"**{new.title}**"] if new.merged else []
        diagrams, bases = [], []
        for old in new.members:
            _link, decision, _builds, diagram = rows[old]
            decision = r.rewrite(decision, ctx)
            decisions.append(f"was {old}: {decision}" if new.merged else decision)
            diagrams.append(r.rewrite(diagram, explicit))
            bases += builds_on(open(r.olds[old].path, encoding="utf-8").read())
        base_nums = sorted({r.new_of(b) for b in bases if b in r.olds} - {new.num})
        link = f"[{new.num}]({new.cat}/{new.file})"
        return f"| {link} | {'<br>'.join(decisions)} | {', '.join(base_nums) or '—'} | {'<br>'.join(diagrams)} |"

    def build_diagrams_readme(self) -> None:
        r = self.r
        text = open(DIAGRAMS_README, encoding="utf-8").read()
        rows = old_rows(DIAGRAMS_README)
        head, rest = text.split("\n## Per ADR\n", 1)
        tail = "## " + rest.split("\n## ", 1)[1]
        ctx = Context(DIAGRAMS_README, DIAGRAMS, DIAGRAMS)
        out = [r.rewrite(head, ctx), "\n## Per ADR\n\n| ADR | Flowchart | Sequence | Shows |\n|---|---|---|---|\n"]
        for new in sorted(r.news.values(), key=lambda n: n.num):
            for old in new.members:
                _link, flow, seq, shows = rows[old]
                link = f"[{new.num}](../adr/{new.cat}/{new.file})" + (f" was {old}" if new.merged else "")
                cells = [link] + [r.rewrite(cell, ctx) for cell in (flow, seq, shows)]
                out.append("| " + " | ".join(cells) + " |\n")
        out.append("\n" + r.rewrite(tail, ctx))
        self.writes[DIAGRAMS_README] = "".join(out)

    def build_rest(self, files: list[str]) -> None:
        handled = set(self.writes) | self.removes
        for path in files:
            if path in handled or path in NOT_REWRITTEN or MIGRATIONS.match(path) or FROZEN.match(path):
                continue
            if path.endswith(".svg") and path != HAND_DRAWN_SVG:
                continue
            try:
                text = open(path, encoding="utf-8", newline="").read()
            except UnicodeDecodeError:
                continue
            folder = os.path.dirname(path)
            new = self.r.rewrite(text, Context(path, folder, folder))
            if new != text:
                self.writes[path] = new

    def changed(self) -> list[str]:
        out = []
        for path, text in sorted(self.writes.items()):
            if not os.path.isfile(path) or open(path, encoding="utf-8", newline="").read() != text:
                out.append(path)
        return out

    def apply(self, changed: list[str]) -> None:
        for path in sorted(self.removes - set(self.writes)):
            os.remove(path)
        for path in changed:
            with open(path, "w", encoding="utf-8", newline="") as f:
                f.write(self.writes[path])


def main() -> None:
    dry_run = "--dry-run" in sys.argv[1:]
    olds, news = read_mapping()
    refuse_if_done(olds, news)
    r = Renumber(olds, news, diagram_files())
    tree = Tree(r)
    tree.build_adrs()
    tree.build_diagrams()
    tree.build_adr_readme()
    tree.build_diagrams_readme()
    tree.build_rest(tracked_files())
    changed = tree.changed()
    gone = sorted(tree.removes - set(tree.writes))
    if not dry_run:
        tree.apply(changed)
    rep = r.report
    print(f"adr-renumber: {len(olds)} old ADRs -> {len(news)} records{' (dry run, nothing written)' if dry_run else ''}")
    print(f"  {len(changed)} files written, {len(gone)} old files removed (renamed or merged)")
    for form, n in sorted(rep.counts.items()):
        print(f"  {n:5d}  {form}")
    print(f"Section references ({len(rep.sections)}):")
    print("\n".join("  " + s for s in rep.sections))
    print(f"Bare numbers in ADRs and .d2 sources ({len(rep.bare)}):")
    print("\n".join("  " + s for s in rep.bare))
    print(f"Wrapped across a line ({len(rep.wrapped)}):")
    print("\n".join("  " + s for s in rep.wrapped))
    print(f"Notes ({len(rep.notes)}):")
    print("\n".join("  " + s for s in rep.notes))


if __name__ == "__main__":
    top = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True)
    os.chdir(top.stdout.strip())
    main()
