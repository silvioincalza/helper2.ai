# Use Case: Search the AI Ecosystem

## Overview

**Use Case ID:** UC-001  
**Use Case Name:** Search the AI Ecosystem  
**Primary Actor:** Visitor  
**Secondary Actors:** Source Provider, Embedding Service, Language Model  
**Goal:** Find tools, models, MCP servers, libraries, papers and news that fit a need with one query, and see which sources answered  
**Trigger:** The visitor types words in the search box, or picks an example search, and starts the search  
**Status:** Reviewed  

**Requirements:** [FR-001, FR-002, FR-003, FR-004, FR-006, NFR-001, NFR-003, NFR-005, NFR-015, C-015](../../requirements.md)  
**Mockup:** [Screens and states](../../mockups/uc-001-search-the-ai-ecosystem.md)  
**API:** [search](../../../api/common.yaml#search), [listCategories](../../../api/common.yaml#listCategories), [listHomeExamples](../../../api/public.yaml#listHomeExamples)

## Preconditions

- At least one source is configured.

## Main Success Scenario

1. Visitor opens the home page.
2. System shows the search box and example searches from the pool of home examples, two for people who use AI and two for people who build with it.
3. Visitor enters a query, or picks an example, and may choose an audience path, filters and a sort order.
4. System checks the query and the chosen filters.
5. System asks every enabled source in parallel, each within its own time limit: the catalog by words and by meaning, the live sources and, when allowed, the live web search. A live source whose recent answer is still fresh is answered from that stored answer.
6. System merges the answers of all sources into one list without duplicates, leaves out results on shadow libraries, results their source states are archived, deprecated or abandoned, and hosted apps that no source confirmed in this search, and records the newly seen results in the catalog.
7. System ranks the results for the query and the audience path, applies the filters and the sort order, and counts the values of each filter.
8. System shows one page of results, the outcome of every source, the coverage, any notices, and the number of web searches the visitor has left today; AI-written text is labelled.
9. Visitor refines the search with filters, including the categories the system lists by family, or asks for the next page.
10. System searches again with the new choices and shows the updated results.

## Alternative Flows

### A1: Invalid Query or Filter

**Trigger:** The query is blank or longer than 200 characters, a category is unknown, or a paging value is out of range (step 4)  
**Flow:**

1. System rejects the search and says what is wrong.
2. Use case ends.

### A2: Too Many Searches

**Trigger:** The visitor has reached the search rate limit (step 4)  
**Flow:**

1. System refuses the search and says when the visitor can search again.
2. Use case ends.

### A3: Query in Another Language

**Trigger:** The query is not in English and query translation is on (step 5)  
**Flow:**

1. System asks the sources that do not need English with the visitor's own words at once.
2. System asks the Language Model to translate the query to English, within the translation time limit.
3. System asks the sources that need English with the translated query, or with the visitor's own words when the translation fails, is late or is refused.
4. Use case continues at step 6.

### A4: A Source Fails, Times Out or Is Paused

**Trigger:** A source returns an error, does not answer in time, reports its rate limit, is paused after repeated failures, or is busy (step 5)  
**Flow:**

1. System marks the source with its outcome and the reason in words.
2. System shows the source's last stored answer, marked as cached with its age, when one is recent enough.
3. System marks the coverage as partial.
4. Use case continues at step 6.

### A5: Daily Web Searches Used Up

**Trigger:** The visitor has no web search left today, or the daily count cannot be checked (step 5)  
**Flow:**

1. System skips the live web search and says why on its source tile.
2. System shows the stored web results for the same query when they are recent enough.
3. Use case continues at step 6.

### A6: No Source Could Be Reached

**Trigger:** No enabled source succeeded and none could serve a cached answer (step 8)  
**Flow:**

1. System shows the notice that no source could be reached instead of an empty result.
2. Use case ends.

### A7: Catalog Unavailable

**Trigger:** The catalog cannot date the results (step 6)  
**Flow:**

1. System shows the results without discovery dates and a notice that the catalog is unavailable.
2. Use case continues at step 7.

### A8: Example Pool Unavailable

**Trigger:** The example pool cannot be read, takes longer than 800 ms, or holds fewer than two examples for a path (step 2)  
**Flow:**

1. System shows its built-in list of examples instead.
2. Use case continues at step 3.

### A9: No Results

**Trigger:** No source returns a result that fits the query and the chosen filters (step 7)  
**Flow:**

1. System says that the sources searched returned no results, lists those sources and the filters that are on, and never says that nothing exists.
2. System offers to remove a filter, to try different or fewer words, or to ask the guide.
3. Visitor removes a filter or changes the words.
4. Use case continues at step 4.

### A10: Recent Answers Unavailable

**Trigger:** The recent answers of the live sources cannot be read or stored (step 5)  
**Flow:**

1. System asks every live source live and shows a notice that the cache is unavailable.
2. Use case continues at step 6.

## Postconditions

### Success Postconditions

- The visitor sees the ranked results and the outcome of every configured source.
- Results newly seen from live sources are recorded in the catalog.
- Fresh answers of live sources are stored for later searches.
- A live web search that answered is counted against the visitor's web searches of the day.

### Failure Postconditions

- A refused search changes no stored data and spends no web search.
- A web search that did not answer is given back to the visitor's daily count.

## Business Rules

### BR-001: Query Length

A query is not blank and holds at most 200 characters.

### BR-002: Paging

A page number is from 0 to 50 and a page holds 1 to 50 results, 20 by default.

### BR-003: Known Categories

A category filter names at most 40 categories, each a category of the category list or "unknown" for results with no category yet. An unknown category is refused.

### BR-004: Search Rate Limit

A visitor makes at most 120 searches a minute by default, counted per network address.

### BR-005: Results per Source

Each source contributes at most 30 results to one search by default.

### BR-006: Source Answers Kept for Failures

A live source's answer is reused while it is fresh for that source. When the source fails later, an answer at most 24 hours old by default is shown as a cached answer. A source that reports its rate limit is paused until the time it states, or one minute when it states none.

### BR-007: Coverage

The coverage is partial when a source that was expected to answer did not succeed. A source that is paused, failing or busy counts as queried; a source left out on purpose (disabled, web search not asked for, nothing to browse) does not count.

### BR-008: Daily Web Searches

The live web search is metered. Each visitor gets 3 web searches a day for search and 3 a day for the AI guide by default, counted per network address and reset at 00:00 UTC. An optional global daily cap of web credits applies to all visitors, with no cap by default. A web search counts only when the web source is called live and answers; an answer from stored web results costs nothing. A web call that was made and failed gives the visitor's web search back but keeps its web credit; a call that was never made gives the credit back too.

### BR-009: Stored Web Results

Stored web results for a query are reused for 24 hours for time-bound searches (news, events or courses only, a date filter, the newest-first order, or time words in the query) and for 48 hours for other searches by default. They are kept 7 days by default and then deleted by a daily job.

### BR-010: Query Translation

A query that is not in English is translated to English by the Language Model within 2.5 seconds by default, at most 300 translations a day by default, and each translation is reused for 24 hours by default. A translation that holds a web address, that talks about the query instead of translating it, or that equals the original words is discarded.

### BR-011: Inactive Results

A result is left out when its source states it is archived, deprecated or not running, when it is a hosted app with fewer than 3 likes, or when it is a fork with fewer than 10 stars not updated within 1095 days, by default. A library, MCP server, skill or agent not updated within 1095 days ranks lower unless it has at least 10,000 GitHub stars.

### BR-012: Audience Path

The audience path (use AI, or build with AI) changes the ranking only; it never hides a result.

### BR-013: Shadow Libraries Hidden

Results on shadow libraries are never shown, as UC-004 BR-006 states.

### BR-014: Examples Shown

The home page shows only the examples UC-003 BR-008 serves.

### BR-015: AI-Written Text Marked

A translated query and every AI-written description in a result are labelled as AI-written on the page and marked in the answer with the provider, the models and the fields they wrote.

### BR-016: Scope Statement

Every search answer states that results come only from the listed sources and not from the whole web.

### BR-017: Sort Orders

Results are sorted by relevance by default, or by newest publication date, or by most recent update. A result whose source states no such date comes last and is labelled as having an unknown date.

### BR-018: Values No Source States

Each filter shows the count of its values and one value for results where no source states the fact. Choosing a value leaves out the results with no stated value; choosing the not-stated value includes them.

### BR-019: Timeline

The timeline shows the same results by month, newest first, by the creation date each source states. Results with no stated creation date form one group at the end; the date helper2.ai first saw a result is never used in its place.

### BR-020: Found Only in a Curated List

A result whose own text does not match the query, but that a curated list names under matching words, ranks below the direct matches and names the list and the heading it sits under.

### BR-021: Needs Named in Another Language

When the query names a known use case with words of another language, the system also searches for the English words of that use case and says so in a notice.

### BR-022: Hosted Apps Confirmed in the Search

A hosted app that only the catalog returns in a search is left out, because the catalog does not know whether the app still runs. It is shown when the live source of hosted apps, another live source or the curated resources return it in the same search.
