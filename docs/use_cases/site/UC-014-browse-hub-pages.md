# Use Case: Browse Hub Pages

## Overview

**Use Case ID:** UC-014  
**Use Case Name:** Browse Hub Pages  
**Primary Actor:** Visitor, Search Engine  
**Goal:** Find the newest resources of one type, and let search engines find every page of searchavo.ai worth indexing  
**Trigger:** The visitor opens a hub page from the footer or from a search engine result, or a search engine reads the site  
**Status:** Approved  

**Requirements:** [FR-020](../../requirements.md)  
**Mockup:** [Screens and states](../../mockups/uc-014-browse-hub-pages.md)  
**API:** [listHub](../../../api/public.yaml#listHub)

## Preconditions

- The catalog holds resources.

## Main Success Scenario

1. Visitor opens the hub page of one type of resource.
2. System shows the name of the type and one line saying that the list holds the resources of that type in the catalog, newest change first.
3. System shows up to 50 resources of that type, each with its title linked to its resource page, and the host and license as their sources state them.
4. Visitor opens a resource page from the list.
5. Search Engine reads the crawler rules, the sitemap and the site summary for language models.
6. System lists in the sitemap the site pages, every hub page that has resources, and every resource page worth indexing with the day of its last change.
7. Search Engine fetches a page of the site.
8. System returns the page with its title, description, structured data and indexing directive already in place.

## Alternative Flows

### A1: The Hub Is Empty

**Trigger:** The catalog has no resource of that type yet (step 3)  
**Flow:**

1. System says that there is nothing here yet, marks the page as not to be indexed, and leaves it out of the sitemap.
2. Use case ends.

### A2: The List Cannot Be Loaded

**Trigger:** The list of the hub cannot be read (step 3)  
**Flow:**

1. System says that the list could not be loaded and offers to try again.
2. Use case ends.

### A3: No Hub for the Type

**Trigger:** The requested type has no hub page (step 1)  
**Flow:**

1. System says that there is no hub page for this type.
2. Use case ends.

### A4: The Site Is Not to Be Indexed

**Trigger:** The site is configured not to be indexed (step 5)  
**Flow:**

1. System allows crawling but offers no sitemap and no site summary, and marks every page as not to be indexed.
2. Use case ends.

### A5: The Resource Address Is Outdated

**Trigger:** The address of a resource page does not match the current title of the resource (step 7)  
**Flow:**

1. System sends the reader permanently to the current address of the page.
2. Use case continues at step 7.

### A6: Unknown Page

**Trigger:** No page or no resource exists at the address (step 7)  
**Flow:**

1. System returns a page that says the page was not found, marked as not to be indexed.
2. Use case ends.

## Postconditions

### Success Postconditions

- The visitor has seen the newest resources of the type.
- Search engines know every page worth indexing and its last change.

### Failure Postconditions

- No page that is not worth indexing is offered to search engines as worth indexing.

## Business Rules

### BR-001: Hub Pages

There is one hub page per type: AI apps (`/ai-apps`), AI models (`/ai-models`), AI agents (`/ai-agents`), MCP servers (`/mcp-servers`), AI libraries (`/libraries`), agent skills (`/agent-skills`), AI datasets (`/datasets`), AI papers (`/papers`), AI prompts (`/prompts`) and AI courses (`/courses`).

### BR-002: Hub List

A hub page lists at most 50 resources of its type that are worth indexing, newest change first. The list is read again at most every 10 minutes; when a new read fails, the last list is shown, and when no list was read before, the hub is shown as empty (A1).

### BR-003: Pages Worth Indexing

Products, agents, models, MCP servers, libraries, skills, datasets, papers, events, courses and prompts are listed for indexing; a prompt only when its description has at least 200 characters. A resource page asks to be indexed only when it has a description that its source, the community or an administrator states, never one written by AI, and, for a type other than a prompt, at least one fact: a license, a price, a publication date, an event date a source states, or a link to another resource. Otherwise it asks not to be indexed but to be followed.

### BR-004: Sitemap

The sitemap lists the site pages (home, sources, events, mission, privacy, terms and support), every hub page with resources, and the resource pages worth indexing in parts of 10,000 pages, at most 200,000 resource pages, the newest change first. It is built again at most once an hour, and the crawler files may be kept by crawlers for one hour.

### BR-005: Crawler Rules

The crawler rules keep crawlers out of the sign-in, sign-out and back office addresses and name the sitemap.

### BR-006: Not Indexing a Site

A site configured not to be indexed (`SA_NOINDEX`) serves no sitemap and no site summary, and every page asks not to be indexed.

### BR-007: Pages Not to Be Indexed

The search, saved, account, sign-in, news and support thank-you pages ask not to be indexed but to be followed; the back office and the not-found pages ask not to be indexed at all.

### BR-008: Address of a Resource Page

A resource page lives at an address made of the resource identifier and a short form of its current title; any other address of the same resource leads to it permanently.
