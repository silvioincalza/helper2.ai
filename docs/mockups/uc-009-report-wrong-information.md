# Screens and states: Report Wrong Information

Screen notes for UC-009: what the page shows, step by step and in each state. The behaviour is in the use case.

## Report wrong or outdated information

1. On a detail page, "Report a problem" → kind (wrong / outdated / broken link / other), optional field, message (marked "required").
2. Submit → 202 "Thanks — a human will review it". Report enters the review queue; nothing on the page changes automatically.

**Failure cases:** validation error inline; rate-limited → friendly message; network error → retry with text preserved.

| State | Behavior |
|---|---|
| Loading | Not specified. |
| Success | 202 "Thanks — a human will review it". The report enters the review queue; nothing on the page changes automatically. |
| Empty | Does not apply: the journey is one form. |
| Partial | Does not apply. |
| Error | A validation error shows inline; rate-limited: a friendly message; a network error: retry with the text preserved. |
