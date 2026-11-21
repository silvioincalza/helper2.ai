# The backlog and the Project

The backlog is the issues of the repository and one GitHub Project of the owner. Both are made from a backlog file
by [`scripts/backlog.py`](../../scripts/backlog.py). The file is kept in `.private/plans/` and not in git: it can hold
the text of items that stay private.

```bash
gh auth refresh -s project
python3 scripts/backlog.py import .private/plans/backlog.json
python3 scripts/backlog.py import .private/plans/backlog.json --write
```

The first command writes nothing: it prints each change it would make. Read it, then run the second.

What the import does:

- One issue per epic, feature, story and task, with the label of its kind. A feature is a sub-issue of its epic, a
  story or a task of its feature.
- An issue is found again by the line `<!-- backlog:ID -->` at the end of its body. So a second run changes nothing,
  and an entry whose title or text changed updates its issue and never makes a second one.
- Only an issue written by the owner, a member or a collaborator counts. The same line in an issue of anybody else is
  ignored, and the run says so. One ID on two issues stops the run.
- Every issue is an item of the Project. The import sets the fields Context, Priority, Release and Replay PR, and
  clears one whose value was taken out of the file. Status
  is the Project's own field: the import checks that it is there and never sets it, because the Project's workflow
  closes an issue whose Status becomes Done.
- A built story stays open. The pull request that builds it closes it with a `Closes #n` line.
- An entry with `"private": "security"` or `"private": "legal"` becomes an issue that says only "Tracked privately",
  with its ID. Its title, text and criteria are never sent to GitHub. A security finding or a point for a lawyer is
  written this way, or stays out of the file.

The backlog file is one JSON object:

| Key | Holds |
|---|---|
| `repo`, `project` | `owner/name` of the repository; the title of the Project |
| `slices` | number of a replay pull request → its name |
| `epics` | `id`, `kind` (`epic`), `title`, `context` |
| `features` | `id`, `kind` (`feature`), `parent`, `title`, `source`, `context` |
| `stories`, `tasks` | `id`, `kind`, `parent`, `title`, `story`, `criteria`, `needs`, `context`, `priority`, `release`, `replay_pr`, and `private` for an item that stays private |

`priority` is one of Replay, Now, Next, Later, Parked, Accepted. Replay means built: the story waits for its pull
request.

A story names the use case it builds in the first line of its `criteria` (`Builds UC-005.`), so the board, the
specification and the code trace to each other. New behaviour needs a use case whose `**Status:**` is `Approved`:
when it is not, the story is created with `needs` set to the approval, not refused. A bug fix, a task and work
with no use case (tooling, operations, docs) name none. The `backlog` agent writes the stories this way.

Two commands read GitHub and write nothing there:

```bash
python3 scripts/backlog.py roadmap
python3 scripts/backlog.py pr-stories <pull request number>
```

`roadmap` writes [docs/roadmap.md](../roadmap.md) from the open issues that have the label story or task; its output
goes into a pull request.
`pr-stories` is run before a replay pull request is merged: it passes only when the pull request closes every story
whose Replay PR field is the number in its branch name (`replay/NN-name`), and no other issue.
