# Screens and states: Browse Hub Pages

Screen notes for UC-014: what the page shows, step by step and in each state. The behaviour is in the use case.

## Browse one type of resource (hub pages)

Entry: a search engine result for a kind of thing ("MCP servers"), or **Browse by type** in the footer of every page
(`/ai-apps`, `/ai-models`, `/ai-agents`, `/mcp-servers`, `/libraries`, `/agent-skills`, `/datasets`, `/papers`,
`/prompts`, `/courses`).

1. The page names the type and says in one line what the list is: the resources of that type in the catalog, newest
   change first.
2. Up to 50 rows: the title, linked to the resource page, and the host and license as their sources state them.
3. Exit: a row opens its resource page; the box in the header searches; the footer goes to another type.

| State | Behavior |
|---|---|
| Loading | The shared list skeleton under the heading. Announced once. |
| Success | Up to 50 rows, newest change first. |
| Partial | Not specified. |
| Empty | "Nothing here yet." The page says `noindex, follow` and is not in the sitemap, so no search engine result leads here. |
| Error | "Could not load this list.", with **Try again**. |
