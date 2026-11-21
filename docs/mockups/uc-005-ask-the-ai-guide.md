# Screens and states: Ask the AI Guide

Screen notes for UC-005: what the page shows, step by step and in each state. The behaviour is in the use case.

## Ask the guide without losing the search

1. With a query and filters on the page, the user clicks **Ask the guide** next to **Search** (any length; the button shows its icon and its words, and says **Ask** on a phone, where Search and Ask sit side by side under the box) or presses Shift+Enter. Enter and **Search** only search, also for a long question: the guide runs only when the user chooses it, because each answer costs model calls.
2. The results show at once; the picks bar above them says the guide is reading. Query, filters, sort and page persist (they live in the URL: `q`, `ask`, filters).
3. The answer arrives: the picks show as a list in the bar, one under the other, each with its reason (labelled AI-written), on a phone too. A pick jumps to its row; a pick that is not in the list opens its resource page. A comparison ("X vs Y") or a "what it is" answer shows open under the bar: the table or the sentences, each with its quote. On a phone (up to 520px) the comparison is one card per aspect, with each tool and its answer under the other, so nothing scrolls sideways. The rows keep the search order, so nothing moves: a picked row shows **Guide pick #1**, the guide's reason (labelled AI-written) and its quote, highlighted in the source's own words with "Checked word for word in the source". The row opens the resource page, which says **Why the guide picked it**: the reason, whether a second model call checked it, and the requirements checked against the source.
4. Nothing fits: the bar says why in one line and offers **Ask without “…”** for each requirement the guide read (when it is short enough to keep in the URL), and **Rephrase**. **How the guide decided** stays folded under the bar: the steps, and how the guide read the question. In the results, the key **g** moves focus to **Ask the guide** and asks nothing; Enter then asks.
5. Back/forward and shared links reproduce the same state. A shared link that asks the guide works on the first visit too: when the browser has no CSRF cookie yet, the page gets it and sends the question once more.

**AI answers left:** once there are words in the box, a quiet line under it says how many AI answers are left today, e.g. "2 of 3 AI answers left today". The numbers come from `limits.guideQuestions` on `/me`; the page reads `/me` again after each answer. The limit is per address, so a signed-in user sees it too. With none left the line says so in words, and when the guide shows search results without AI picks again (the next 00:00 UTC, in the user's own clock). The button still works and Enter still searches. No line when the guide has no model, or for a test environment's high ceiling while answers are left.

**Success:** zero re-typing; URL fully describes the state. A new question while one is being answered replaces it.
Changing the words in the box searches again and drops the old question with its picks: the picks answered that question, not the new words. Shift+Enter or **Ask the guide** asks again.
**Failure case:** the guide fails or is unavailable, or its stream stalls for 45 s or ends without an answer → the search results stay, the answer area says what went wrong and offers **Try again**.

| State | Behavior |
|---|---|
| Loading | The results show at once; the picks bar above them says the guide is reading, with skeleton lines where the picks will be. |
| Success | The picks in the bar, each with its reason (labelled AI-written) and its quote, checked word for word; a comparison or a "what it is" answer shows open. |
| Empty | Nothing fits: the bar says why in one line and offers **Ask without “…”** and **Rephrase**. |
| Partial | Not specified. |
| Error | The guide fails, is unavailable, or its stream stalls for 45 s or ends without an answer: the search results stay, the answer area says what went wrong and offers **Try again**. |
