# Screens and states: The back office

Screen notes for the administrator's use cases (UC-003, UC-004, UC-010, UC-013, UC-018, UC-022, UC-024, UC-025, UC-027, UC-028): what the page shows, step by step and in each state. The behaviour is in the use case.

## Keep the catalog honest

**Entry:** the admin host, `/admin` (only over the VPN, [ADR 0036](../architecture/decisions/ADR-0036-identity-and-the-admin-host.md)).

1. Admin signs in (role ADMIN from an allow-list), opens **Admin**. A sub-navigation links the parts: **Overview**, **Review**, **Scout runs**, **Enrichment**, **Examples**, **Audit**, **Settings**. Each part has its own address.
2. **Overview** (the landing page, [ADR 0038](../architecture/decisions/ADR-0038-back-office-and-review.md)): what needs a person now. How many review items wait, per kind; each count is a link to the queue filtered by that kind (`/admin/review?kind=USER_REPORT`). The enrichment backlog, the failed Scout runs of the last 24 hours (a link to the runs), today's model budgets ("120 of 500", "Off" at 0, "Unknown" when the shared counter cannot answer), web credits today, the last Scout runs, and a link to Grafana for trends. How many home examples are hidden now: per check (almost, in the top 10; not in the top 10; waiting for the check), each a link to the Examples page filtered by that check (`/admin/examples?outcome=FAIL`), and how many are paused. "Shown now" says how many each path has; a path with fewer than 2 gets the Examples page's warning, with a link to the examples. "Counted at" says when; Refresh asks again (the server counts at most once a minute).
3. **Review**: enrichment outputs that failed validation or contradict a source, unverified relationship candidates, user reports, course candidates. Filters by status and by kind; filters and page live in the address, so Back works. The list is on the left, the selected item on the right (stacked on a narrow screen). The first item is selected; a click on its reason selects another. The selected item shows its details, a note field, **Accept** and **Reject**, and its **Evidence** ([ADR 0038](../architecture/decisions/ADR-0038-back-office-and-review.md)):
   the run (model, prompt, outcome, when it finished, the README it also read), what the code check refused (labelled "Includes model output", since it quotes the model), and the fields the run stored, conflicts first, each with its origin in words ("Extracted by AI", "AI-estimated") and up to 5 quotes, each labelled "Source quote", with their source and date. A relationship candidate shows the relationship and its quotes. A user report or a candidate page has no run: the panel says so.
   Keys, only while focus is in the queue and never in a field: **j** / **k** next and previous item, **a** accept, **r** reject, **n** the note (Escape goes back to the list), **?** the list of keys (**Keys (?)** opens it too). A held key counts once: holding **a** accepts nothing more. After a decision the next item is selected and takes focus, and a screen reader hears "Accepted. 11 left."
   **Several at once**: each open row has a box (**x** picks the selected item). A bar under the list says "4 selected · Accept · Reject · Clear". Accept or Reject opens a dialog that repeats the count by kind ("3 Enrichment validation · 1 User report"), with focus on Cancel. Accept says that only enrichment checks with no conflict on licence, open source or pricing are accepted together; the others stay open. After the decision a line says "11 done, 1 failed (already decided)"; the items that failed stay picked. A new filter, or Clear, unpicks everything. Decisions are final.
   **By target name**: with the kind Relationship candidate, the queue opens grouped by the name the relationships
   point at, the largest group first, with how many open items each has. One answer decides the whole group: **It is a
   resource in the catalog…** (pick it), **It is a service**, **It is a tool**, **It is a vendor**, or **Not a real
   target: reject**. A tool is an application a resource runs in or plugs into, such as VS Code. After the answer a
   line says "Cursor: 117 items decided, 0 skipped, 0 left." and takes focus, and the next group is shown.
   **Each item** goes back to the items one by one.
   **Research with AI** ([ADR 0048](../architecture/decisions/ADR-0048-review-research-assistant.md)): for a resource or course
   candidate. The research bar starts it for the picked items, for this page, or for every open candidate. This page
   and the picked items leave out what is already researched or running: "Research this page (12)" says "12 new, 8
   already researched". **Research all open…** shows how many items it queues and the most model calls they can
   take before anything starts. Each row shows its research in a pill: Queued, "Running · step 4", Failed with the
   reason, Cancelled, or the verdict ("AI: Keep", "AI: Reject", "AI: Duplicate"). A queued or running row has
   **Watch live** and **Cancel**; a finished or cancelled one links to its steps and offers **Research again**.
   The **Research** filter asks the server across every page: AI: Keep, AI: Reject, AI: Duplicate, Queued
   or running, Not researched. A new filter goes back to the first page.
   A researched item shows the proposal under "Proposed with AI.", then the verdict and its reason, "Already listed
   as" with a link for a duplicate, and one row per value: now (struck through), proposed, its status (Confirmed,
   One source, Conflict, Not found) and the source quotes. Each changed value needs a decision: **Confirm** (a
   conflict needs a pick first), **Edit** or **Keep now**; keys **c**, **e**, **u**, and the queue keys do nothing
   inside the proposal. Focus moves to the next value; **Undo** gives it back to that value; **Keep the rest as now**
   keeps every value left. After the last decision focus moves to **Accept with the confirmed values**. Until then
   Accept says "Decide 3 more changes to accept.", also when **a** is pressed. The decisions stay with the run when
   another item is selected and after a look at the steps. **Show every step** opens the steps page: every request
   and response as plain text, filtered by kind or errors, each box scrollable with the keyboard; the model's
   answers are marked "Written with AI". A running research is followed live. **Back to the review queue** returns
   to the same filter and page.
   **Reject 3 the AI marked as reject…** lists those items of the page with their reasons, under "Reasons
   written with AI."; any can be unticked, and nothing is rejected before **Reject 3 items**. Keep and duplicate are
   decided one item at a time.
4. **Scout runs**: sees run history with items seen/changed and failures. **Run all now** starts every enabled collector; **Run now** on a source's newest run starts only that source. The listing follows a started run until it ends. One run at a time: while a start is pending or a run is running, every start button waits, and the page says a run is in progress.
   **Enrichment**: what the Scout enriches next, in its order: the ones I moved to the top first, then never-run resources, then the ones to try again, the most popular first. Each row: the resource (a link to its page), its status in words (At the top, Not run yet, Model was unavailable, Retry after a failure), when it was found, how many runs failed, the last run, and its actions. **Move to top** puts it first until its next run (at most 50 at the top). **Skip** asks first, with an optional reason (the hint says "No personal data") and focus on the reason field; Cancel gives focus back to Skip. **Show** switches the view: waiting, **Skipped** (the latest skip first, with when and why) or **Out of retries** (its runs failed 3 times). In those two views **Requeue** puts the resource back in its own place; one out of retries gets 3 more tries. After an action a line says what happened ("“Acme MCP” is at the top. The Scout takes it in its next run, within the daily limit.") and takes focus, and the list reloads. An action changes the order only, never the daily limit. Next page and First page; the view and the page live in the address. The overview's backlog count links here. Empty: "Nothing waits for enrichment." / "No resource is skipped." / "No resource is out of retries."
5. **Examples** ([ADR 0028](../architecture/decisions/ADR-0028-home-examples.md)): the example searches the home page picks from. One line
   says how many are shown now for each kind (people who use AI, builders); a warning when a kind has fewer than 2.
   **Add an example**: the label on the chip, what it searches, for whom, and 1 to 3 expected results found with a
   catalog search. Each row says its check in words: "Shown · expected result at #2", "Hidden · best expected result
   at #7", "Hidden · no expected result in the top 10", "Waiting for the check". **Edit** loads it into the form;
   **Pause** / **Activate**; **Remove** asks first, with focus on Cancel. The check filter lives in the address
   (`/admin/examples?outcome=FAIL`). A new or changed search waits for the check, up to 5 minutes.
   **Draft examples with AI** asks a model for examples for well-known resources that no example expects yet: "3 drafts
   wait in the Review queue" with a link to them (`/admin/review?kind=EXAMPLE_CANDIDATE`), or why there is none (drafts
   off, today's runs used, the model cannot answer). In the queue a draft shows its label, what it searches, for whom
   and "Drafted with AI (model)"; **Accept** adds it as an example, which then waits for the check.
6. **Audit**: what administrators did, newest first: when, the action, the target, the outcome, who. A discovered list the system removed because its page is gone is listed too, with "System" as who.
7. **Settings** (read-only, [ADR 0038](../architecture/decisions/ADR-0038-back-office-and-review.md)): what the app runs with now, one table per
   part (Models, AI guide, Search, Scout, Scout sources, Setup guide, Home examples). Each row: the setting in words,
   its value ("500 calls a day", "No cap", "On", "None" when no model is set) and the `SA_` variable that sets it, or
   "No variable". Secrets are never shown. A change needs a new value and a restart, except for the spending
   limits at the top ([ADR 0007](../architecture/decisions/ADR-0007-configuration-secrets-runtime-settings.md)): Scout enrichments per run, enrichment
   calls and gap rounds a day, the guide's model calls a day, translations a day, Live web credits a day for all of
   searchavo.ai and for the Scout's searches, page reads a day, research model calls a day. Each row:
   the value now with "Changed" or "Default", the default and its variable, the allowed range ("0 to 3000 calls a
   day") and one line of help. **Change** opens a number field; a value outside the range says "Enter a whole
   number from 0 to 3000." and nothing is sent. **Review changes** shows "600 calls a day → 50 calls a day", says
   when 0 turns the use off, and asks for an optional reason; focus moves to the review. **Confirm change** saves:
   "Saved: … is now 50 calls a day." **Reset to default** and **Restore** (in History, the last 10 changes with
   who, when and why) go through the same review. Someone changed the limit first: "Someone changed this limit
   first." and the row shows the current value. Every replica applies a change within about 20 seconds. The
   overview says "2 spending limits changed at runtime", a link to this page; the audit trail shows each change
   as "default 600 → 50" with its reason.
   **Switches you can change**, above the limits: "Google ads" ([ADR 0042](../architecture/decisions/ADR-0042-monetisation.md)), with
   its value now (`true` or `false`) and "Changed" or "Default", the default and `SA_ADS_ENABLED`, and one line on
   what true and false do. **Set to true** (or **Set to false**) opens the same review: "Google ads: false → true",
   an optional reason, **Confirm change**: "Saved: Google ads is now true." **Reset to default** goes through the
   same review. History lists the last changes as `true`, `false` or "back to the default". Someone changed it
   first: "Someone changed this switch first." The audit trail shows "default false → true". The overview counts
   limits only.
   **Known vendors, services and tools** ([ADR 0019](../architecture/decisions/ADR-0019-known-targets-vendors-and-services.md)): the names a relationship may point
   at with no catalog resource, one table each for vendors, services and tools. Each row: the name, its other names, how
   many relationships carry it, who added it and when. **Add name** takes a kind, a name and other names, one per
   line; an empty name says "Enter a name, such as Slack." and nothing is sent; a name another target holds
   says "Not saved: Open AI already means OpenAI." **Change** opens the stored kind and names and says that a
   relationship under a name taken away goes back to the review list. **Remove** first says "3 relationships
   carry it. They become unverified and go back to the review list, one item each.", then asks. Focus moves to
   the form or the question; Cancel closes it.
   A name the Scout proposes waits in **Review** as a "Known target proposal": the owner of 5 or more GitHub or
   Hugging Face resources. The card shows the name, the kind, "The owner rule: 22 GitHub or Hugging Face resources
   have this owner." and up to 5 of them. **Change the proposal** opens the kind, the name and the other names:
   `meta-llama` becomes "Meta" with `meta-llama` as another name; the card then says "Changed by an admin."
   **Accept** adds it to this list, marked "proposed by a rule". When the name is one a vendor or service of the
   same kind already has, Accept adds the other names to it instead. A name a target of another kind holds says so
   and the item stays open. **Reject** is remembered: the name is not proposed again.
   A person's account is rejected.
   **Shadow libraries** ([ADR 0049](../architecture/decisions/ADR-0049-shadow-libraries.md)): sites that copy books and papers without
   the rights holder's permission. A web result on one of them is never shown, and the Scout never proposes it.
   **Add shadow library** takes a name and an optional reason; an empty name says "Enter a name, such as libgen."
   and nothing is sent. Each row says who added it and when. **Remove** asks first, with an optional reason; Cancel
   gives focus back to that row's Remove, and after the removal focus moves to the list heading.

Each item is a row: the kind, the resource, the reason, the date and the status in words in a pill. The selected row has a saffron bar. Each run shows its status in a pill too. One action runs at a time; the other buttons wait.

| State | Behavior |
|---|---|
| Success | After a decision the next item is selected and takes focus, and a screen reader hears "Accepted. 11 left." |
| Loading | Skeleton rows in each part, said as "Loading…"; "Loading the evidence…" and "Loading the research…" where those load. On Refresh the overview keeps showing the last counts, and the shadow libraries keep their rows while they reload. On Settings a screen reader hears one "Loading…" for the page, not one per part. |
| Empty | "Nothing in this queue." / "No runs yet." / "No entries yet." A research filter that matches nothing: "No item matches this research filter." with **Clear the research filter**. After the last decision focus goes to the queue's heading. An item with no run: "No run or relationship behind this item." A decided run whose fields a newer run replaced: "This run has no stored fields left." |
| Error | "Could not load the overview." (or the review queue, the Scout runs, or the audit trail) with Try again; focus stays on the part's heading. "Could not load the evidence." with Try again in the selected item; the list and the decision still work. A reload of the Scout runs that fails keeps the last list and says so, with Try again. A Refresh of the overview that fails keeps the last counts and says so. An action that fails says so above the queue or the runs; a start refused because a run holds the lock says a run is in progress. |
| Already decided | Someone else decided the item first (or a newer enrichment run replaced it): "This item was already decided." The list reloads and shows its current status. |
| Queue action refused | The resource changed meanwhile, or the top is full: "Nothing changed: 50 resources already wait at the top." (or: is already skipped, does not wait, is neither skipped nor out of retries). The message takes focus and the list reloads. |
| Partial: bulk decision partly done | "11 done, 1 failed (already decided)." or, with several reasons, "(1 already decided, 2 not allowed in bulk)". The failed items stay picked; the list reloads. A request that fails says "The action failed." and keeps the items picked. |
| Example refused | The server's rule in words: "Not saved: query: no web addresses." A duplicate label or query: "Another example already has this label or this query." Someone changed the example first: the list reloads, and the change is made again. |
| Live stream stopped | The steps page heard nothing for 45 seconds, or the stream broke while the research runs: "Live updates stopped." with **Reconnect**, which loads the steps again and follows from the last one. |
| Research budget spent | The daily model calls for research are used up: queued runs wait for the next day. They can be cancelled at any time. |
| Research no longer kept | A research run and its steps are kept 90 days. An older or unknown run: "This research is no longer kept (90 days)." Another error: "Could not load the research. Try again." with Try again. |
| Unknown filter in the address | Falls back to open items of every kind, first page. |
| Not an admin | A signed-in user without the role sees that an administrator account is needed, with a link to the home page. No admin endpoint is called. |

**Exit:** any link back to the public site, or the next part in the sub-navigation.

**Failure cases:** LLM provider unavailable → runs complete with `LLM_UNAVAILABLE`, original source metadata stays published; one connector failing does not stop others.
