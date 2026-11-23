# Screens and states: Support helper2.ai

Screen notes for UC-016: what the page shows, step by step and in each state. The behaviour is in the use case.

## Support helper2, ads and the analytics question

**Entry:** "Support helper2" in the footer; the analytics bar on the first visit; the "Sponsored" box on a search page
([ADR 0042](../architecture/decisions/ADR-0042-monetisation.md)). Each part shows only while its setting is on.

1. **Give.** The Support page says what running helper2 costs and offers the ways that are on: "Sponsor on GitHub"
   (new tab) and "Give by card" (Stripe). The page says that both go to the provider's own page, and that giving
   changes nothing on helper2.
2. Stripe sends the giver back to `/support/thanks`: "Thank you", and a link back to search. No upsell.
3. **The analytics question.** A bar at the bottom: "Can we count your visit with Google Analytics?", with "Reject" and
   "Accept" the same size. Search works before an answer. The answer is kept; "Cookie settings" in the footer asks
   again. With the keyboard the bar is the first stop after "Skip to main content", and the page keeps a focused item
   above it. On a short screen (up to 480px tall) the bar sits at the top of the page and scrolls with it.
4. **Google's question** (while AdSense is on, in place of step 3): where the EU, EEA, UK or Swiss rules apply,
   Google's consent tool asks once, for ads and analytics, on the first page of a visit that shows an ad: a resource
   page, a search or a guide question. Home and the other pages never show it. "Privacy choices" in the
   footer asks again from any page ([ADR 0042](../architecture/decisions/ADR-0042-monetisation.md)).
5. **The ads.** One box marked "Advertisements" on a resource page, between its sections, and one below the results
   of a search, after the list and "Show more". One search, one ad: a filter, a sort or the timeline keeps it, new
   search words show a new one. A guide question shows one box below the guide's picks and answer instead, once
   the answer is there and has a pick. No ad while results load or the guide works.
6. **While a slow search loads**, a note from helper2.ai about supporting the site can show above the loading
   results. It is not an ad; the results replace it the moment they come.

**Exit:** the provider's page, the link back to search, the box.

| State | Behavior |
|---|---|
| Loading | No ad while the first results load. After 700 ms the note from helper2.ai can show. |
| Success | The Support page offers the ways that are on; after a gift, `/support/thanks`. A search with results shows one ad below them. |
| Empty: nothing configured | No Support link, no bar, no ad box: the site as before. |
| Partial | Not specified. |
| Support page, no way on | "Giving is not open yet." |
| Payment cancelled at Stripe | Stripe's page; back on the Support page, no message and no nagging. |
| No ad to show | The box keeps its height and stays empty. It is below the results, so they do not move. |
| Search failed, found nothing, or no source could search | No ad box: the state is all the page shows. |
| Ad or analytics blocked by the browser | Nothing breaks; search works the same. |
| AdSense on, the visit never opens a page with an ad | No Google dialog, and the visit is not counted in Google Analytics. |
| The guide asks back, picks nothing or fails | No ad box. |

**How we know it worked:** gifts and amounts from the providers' dashboards; views of `/support` and
`/support/thanks` in Google Analytics (for visitors who accepted; while AdSense is on, only in visits that opened a
page with an ad); p95 time to results and CLS before and after the ad
slot. If either gets worse, the slot goes.
