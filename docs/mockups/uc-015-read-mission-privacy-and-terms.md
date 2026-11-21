# Screens and states: Read Mission, Privacy and Terms

Screen notes for UC-015: what the page shows, step by step and in each state. The behaviour is in the use case.

## Read the mission, the privacy notice and the terms

1. The footer links to **Mission**, **Privacy** and **Terms** on every page. Each opens a text page: the title, the date of the last change (Privacy, Terms), a short lede, the contact, then the sections, with **On this page** beside the text on a wide screen and folded under the head on a phone.
2. The numbers in the text come from the server (GET /policy): how many candidates and picks the guide uses (Mission), the daily limits (Terms) and how long each store keeps data (Privacy). They are the numbers the code applies, never typed ones. Periods read as "24 hours", "7 days", "24 months".
3. The page and its numbers load together; the page waits for them at most half a second, so it usually opens with them in place.

| State | Behavior |
|---|---|
| Loading | The numbers are late: the sentences say it in words ("a daily limit", "for a limited time"), then the numbers take their place. No wrong number, no placeholder in the text. |
| Success | The page opens with the server's numbers in place. |
| Empty | Does not apply: the pages are text. |
| Partial | Numbers the server does not give (the 00:00 UTC reset, counter lifetimes, logs, backups, usage totals) stay typed in the text. |
| Error | The same sentences in words, and one line under the head: "Some limits and periods could not be loaded, so this page states them without numbers. Reload the page to see them." |
