# Screens and states: Save Resources

Screen notes for UC-012: what the page shows, step by step and in each state. The behaviour is in the use case.

## Save resources

1. Anonymous user saves a resource with **Save** on its result row or on its page → stored in `localStorage`; visible on **Saved**.
2. If storage is unavailable (private mode), the toggle explains saving is unavailable in this browser.
3. Signed out, the Saved page says "Saved in this browser only." and offers sign-in. After sign-in ([UC-011](uc-011-sign-in-and-manage-account.md)) the browser's saved items move to the account once and are removed from the browser; from then on they follow the account across devices. If the merge fails, the browser's items stay on screen with **Try again**.

| State | Behavior |
|---|---|
| Loading | Signed in, skeleton rows of the list while the saved items load. |
| Success | The resource is on **Saved**; signed in, saved items follow the account across devices. |
| Empty | Not specified. |
| Partial | The merge into the account fails: the browser's items stay on screen with **Try again**. |
| Error | Storage is unavailable (private mode): the toggle explains that saving is unavailable in this browser. |
