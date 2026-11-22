# Use Case: Read Lists

## Overview

**Use Case ID:** UC-030  
**Use Case Name:** Read Lists  
**Primary Actor:** Scout  
**Secondary Actors:** Source Provider  
**Goal:** Read the lists of AI resources, awesome lists on GitHub and list pages on the web, follow each link to the item's own source, and keep which list names which resource, so that what a list names reaches the catalog checked and a visitor sees how known a resource is.  
**Trigger:** A pass of the awesome-list collector starts in a run of the Scout (UC-018).  
**Status:** Approved  

**Requirements:** [FR-041, NFR-015, C-008](../../requirements.md)

## Preconditions

- The awesome-list collector is switched on and due (UC-018 BR-004).
- The discovery hosts can be read (UC-019).

## Main Success Scenario

1. Scout gathers the lists to read: the GitHub lists and web pages an administrator added (UC-020), the list pages discovery added on directory hosts, and the top lists its own search finds.
2. Scout reads each GitHub list from the Source Provider, and each web page that is due through the live web page reader.
3. System keeps the text of the last read of each web page.
4. Scout follows the lists a list links to, within the limits of a pass.
5. Scout takes every link of every list entry, follows a link of a directory list that does not point at the item to the item's own page (BR-013), and sorts each link by its kind.
6. Scout asks the Source Provider for each repository, model, paper and package by its id.
7. System indexes what the Source Provider confirms, by the admission rules (UC-018).
8. System proposes the other links (product sites, course pages, articles, and items under the index floor) as candidates in the review queue (UC-024), within the daily cap.
9. System adds each list as a resource of the catalog, except a list page of a directory host.
10. System records which list names which resource, with the headings above the entry, in place of what the list named before, and the rating a directory states for it (BR-010, BR-011).

## Alternative Flows

### A1: Web Page Not Due or Waiting

**Trigger:** A web page was read less than 7 days ago, the reads of the day or the credits are spent, or the page reader is off (step 2)  
**Flow:**

1. Scout does not read the page.
2. System uses the text of its last read; a page never read gives nothing.
3. Use case continues at step 4.

### A2: Web Page Cannot Be Read

**Trigger:** The page reader fails, answers with no text or answers from another host (step 2)  
**Flow:**

1. System counts the failed read and keeps the text of the last successful read.
2. Scout asks the site for the status of the page, with a status request of its own (BR-014).
3. System counts the answer when it says that the page is gone (BR-012).
4. Scout tries the page again by the rule of failed reads (BR-002).
5. Use case continues at step 4.

### A3: Host Never Read

**Trigger:** A web page is on a host the configuration names as never read (step 2)  
**Flow:**

1. Scout does not read the page and does not use its last read.
2. Use case continues at step 2.

### A4: Reading of the Host Turned Off

**Trigger:** A list page discovery added is on a directory host whose switch is off, or on a host that is no longer a directory host (step 2)  
**Flow:**

1. Scout does not read the page.
2. System uses the text of its last read, so what the list named stays.
3. Use case continues at step 4.

### A5: Source Does Not Confirm

**Trigger:** The Source Provider does not know the item, or the item fails the checks of its source (step 6)  
**Flow:**

1. System adds nothing for this link.
2. Use case continues at step 5.

### A6: Budget of the Pass Spent

**Trigger:** The time or size budget of the pass is used up, or a source limits its calls (step 2, step 4 or step 6)  
**Flow:**

1. Scout stops reading; the lists and links left wait for the next pass.
2. Use case continues at step 7 with what was read.

### A7: Followed Link Gives Nothing

**Trigger:** After 3 steps no page that is the item's own is found, a step is refused or fails, or the check says the page is not the item's (step 5)  
**Flow:**

1. System keeps that the link was followed and gave nothing.
2. System adds nothing for this link.
3. Use case continues at step 5.

### A8: Link Waits to Be Followed

**Trigger:** The steps of the day, the credits, the budget of the pass or the status requests of the day are spent (step 5)  
**Flow:**

1. Scout does not follow the link; it is followed at a later pass.
2. Use case continues at step 5.

### A9: Web Page Gone at Its Site

**Trigger:** The site of a web page that cannot be read answers that the page is gone, on 3 checks in a row, the first at least a day before the last (step 2)  
**Flow:**

1. System removes what the page named and the ratings its page stated (BR-012).
2. System drops the text of the last read of the page; a list page discovery added is no longer a list to read.
3. Use case continues at step 4.

## Postconditions

### Success Postconditions

- Every list that was read is a resource of the catalog, except a list page of a directory host.
- Every repository, model, paper and package a list names and its source confirms is in the catalog; every other kept link waits in the review queue.
- The catalog holds which list names which resource.
- The catalog holds the rating figures the directory hosts state for the resources their lists name.
- The text of the last read of each web page is kept for the passes until its next read.
- What following gave for each link of a directory list is kept until the list is read again.

### Failure Postconditions

- A list that could not be read keeps what its last read gave, until it is gone at its source.
- A list that is gone at its source names no resource any more (BR-012).
- No item enters the catalog from a link alone.

## Business Rules

### BR-001: Reading an Added Repository

The awesome-list collector reads every GitHub repository an administrator added (UC-020) at the start of each of its passes, before the lists its own search finds, whatever the repository's stars and name.

### BR-002: Reading an Added Web Page

An added web page is read through the live web page reader at most once every 7 days by default. After a failed read, the next try comes after 1 hour by default, for up to 3 failed tries; then the page waits the full 7 days again. The text of the last successful read is kept and used while the page is not due, waits or cannot be read, until the page is gone at its site (BR-012). A page waits when the page reader is off, its daily credits are spent, or the time or size budget of the collector's pass is used up. A page on a host that is never read (UC-020 BR-002) is not read and its last read is not used.

### BR-003: A List Page Discovery Finds

The searches of discovery do not exclude a directory host whose switch is on (UC-019 BR-007), so that its list pages can be found. When discovery finds a page whose title reads as a list on such a host, it adds the page as a web page to read, stored with the discovery collector that found it and no reason. The page follows the rules of a web page an administrator adds: an accepted address (UC-020 BR-001) and a host that is not one never read (UC-020 BR-002). At most 100 such lists are added by default; they do not count against the lists an administrator adds (UC-020 BR-003). A page these rules refuse, and a page found when the 100 are added, waits in the review queue as an article candidate, as with the switch off (UC-019 BR-001). A page that is a list to read already is not added again.

### BR-004: Reading the Lists Discovery Added

A list discovery added is read like an added web page (BR-002). In each pass the web pages an administrator added are read first. At most 10 lists discovery added are read per day by default; the others wait for the next day and use the text of their last read meanwhile.

### BR-005: Reading Turned Off

A list discovery added is read only while the switch of its host is on. When the switch is turned off, or the host is removed from the directory hosts, the list is no longer read. What its last read gave stays: the list still names its resources, and it stays a list of a directory host (BR-009). Turning the switch on again, or adding the host again with the switch on, resumes the reading.

### BR-006: Each Link Follows the Rule of Its Kind

A link of a list never enters the catalog by itself. A GitHub repository, a Hugging Face model, an arXiv paper and an npm package are asked of their own source by id, and indexed when the source confirms them and the admission rules allow (UC-018 BR-005, BR-006). A course page on a known learning platform and a product's own site become candidates in the review queue. A link that redirects, a link to a directory host and a link to an excluded domain are skipped, with one exception: on a list page of a directory host whose switch is on, a link to the directory's own page of the item and a link that redirects are followed to the item's own page (BR-013), and the page found follows the rule of its kind. No page of a directory host other than an added list page is read, except the directory's own page of an item that a read list names: for its rating (BR-011) and for the link to the item's own page (BR-013). Nothing else of that page is kept. New candidates stay within the daily cap of the collector (UC-018 BR-011).

### BR-007: Links to Articles

A link of a list that reads as an article by the rule of discovery (a post on a writing host or below a writing place of a site) becomes an article candidate in the review queue. A blog's index, an author's page, a page on a directory host and a page on an excluded domain are skipped.

### BR-008: Which List Names Which Resource

For each list that was read, the catalog keeps the resources it names that are in the catalog, whatever their kind, with the headings above each entry: a repository, a model, a paper and a package, and also a product, a course and an article its links point to. A candidate a list proposed keeps the list that named it; when an administrator accepts it (UC-024), the resource is named by that list from that moment, unless the list is a curated list that is gone at its source (BR-012). The record of a list is replaced at each pass that covers the list and has no retention period: when a list is no longer read because an administrator removed it, its reading is turned off or its host is never read, what it named at its last read stays. A record is removed only with the resource it names, for a curated list with the list's own resource, or when the list is gone at its source (BR-012). A curated list (an awesome list on GitHub, or a web page that is not on a directory host) is itself a resource; the public page of a resource names the curated lists that name it (UC-007 BR-008).

### BR-009: A List Page of a Directory Host Is Never a Resource

A list page of a directory host is never a resource of the catalog, whoever added it, and a list discovery added stays one after its host is removed from the directory hosts: it has no page, it is in no search result and it is not counted among the resources. The catalog keeps only its address, its title and the resources it names. The public page of a resource gives the number of such lists that name it (UC-007 BR-008); the back office names them (UC-024 BR-019).

### BR-010: The Rating a Directory States

For each entry of a list page of a directory host that names exactly one resource of the catalog, the system takes the aggregated rating the page states in the text of the entry: the average, its scale (5, 10 or 100) and the number of votes, reviews or ratings. All three must be stated; a figure with no stated scale is dropped, and a count of upvotes or likes alone is not a rating. A value outside its range is dropped: the average above 0 and at most its scale, the count from 1 to 100,000,000. Code reads the figures by fixed patterns, never a model. No text of a review and no name of a reviewer is read or kept. The catalog keeps the host, the address of the page that states the figures, the average, the scale, the count and the time of the read. A record is replaced when its page is read again, removed when the list no longer names the resource, removed when its list page is gone at its site (BR-012), and removed with the resource.

### BR-011: Reading the Directory's Page of an Item

When an entry states no rating and links to exactly one page of the same directory host about the item, the system reads that page through the live web page reader and takes only the rating figures from it (BR-010). Such a page is read only when the switch of its host is on (UC-019 BR-007), the host is not one never read (UC-020 BR-002) and the resource is in the catalog. At most 100 such pages are tried per day by default, apart from the reads of lists (BR-004); every try counts, also one that fails or states nothing. A page is tried again no more often than its list is read (7 days by default). A read costs one live web credit of the Scout's daily limit; when the limit is spent, the pages wait. A page that the following of links read in the same pass (BR-013) is not read again: its rating is taken from that read, which counts as a try and costs no further credit. A visit to a page of helper2.ai never starts a read.

### BR-012: A List Gone at Its Source

A list that is gone at its source names no resource any more: what it named is removed, on the public page of a resource (UC-007 BR-008), in the number of directory lists that name it and in search.

A curated list on GitHub is gone at its source when GitHub answers that its repository does not exist. The repository sweep asks this for the repository of every such list, whoever found the list (UC-018 BR-009). When the sweep does not find the repository on 3 visits in a row by default, and the repository is marked gone (UC-018 BR-009), what the list named is removed. Only the answer "not found" counts. A visit that fails for another reason (no answer, a limit on calls, an error) removes nothing and does not count. A visit that finds the repository starts the count again, and a renamed repository is found under its new name. The list's own resource follows the rule of a repository that is gone (UC-018 BR-009) and is not deleted. A candidate the list proposed that an administrator accepts after that is not named by it (BR-008). When the repository exists again, the next read of the list records again what it names.

A web page, a curated one or a list page of a directory host, is gone at its source when its site answers 404 or 410 to a status request of the Scout's own (BR-014). The live web page reader does not say that a page no longer exists, so the site is asked, and only after a read of the page through the page reader failed (A2). A page that was read is never asked about. Neither is a page that is not read: one that is not due or waits (BR-002), one whose reading is turned off (BR-005) and one on a host that is never read (UC-020 BR-002) keep what they named. When the site says that the page is gone on 3 checks in a row by default, the first at least one day before the last, what the page named is removed, with the ratings its page stated (BR-010) and the text of its last read. Only 404 and 410 count. Any other answer removes nothing and does not count: another status (an error of the site, a refusal, a limit on calls), no answer, and a request that was not made because the robots.txt of the site, a cap or the host check of BR-014 refused it. A page that answers with success or with a redirect is not gone: the count starts again, and the redirect is not followed. A read that gives the page starts the count again too. The own resource of a curated web page follows the rule of a repository that is gone (UC-018 BR-009): it is marked gone and is not deleted, and a later read that gives the page takes the mark off. A page an administrator added stays a list to read and is tried again by the rule of failed reads (BR-002). A list page discovery added is no longer a list to read: it frees its place among the lists discovery adds (BR-003), and discovery adds it again when it finds it again. When the page exists again, its next read records again what it names. A candidate a curated web page proposed that an administrator accepts after the page is gone is not named by it (BR-008); a candidate a list page of a directory host proposed still is.

### BR-013: Following a Link of a Directory List

On a list page of a directory host whose switch is on (UC-019 BR-007), an entry with no link that points at its item is followed, when the words of its link name the item: a link to a page of the list's own host (not the list, not the front page), and a link that redirects (a URL inside the path or the query, a path such as `/out` or `/go`, a link shortener). Following takes at most 3 steps for an entry. A step is one request made for the entry: a read of the directory's page of the item through the live web page reader, a status request of the Scout's own for a link that redirects (BR-014), or a read of the page reached through the live web page reader. Of the directory's page of the item one link is taken: the first whose address carries the name of the entry, else the first whose words say that it goes to the site ("Visit website"). At most one page of the directory is read for an entry. Every address on the way passes the Scout's host check again (https, a public host name, no IP address, no private or reserved name, no punycode, no user info, port 443 only); an address on a directory host or in a shadow library gives nothing. The page reached is the item's own when the name of the entry is a whole part of its address (the name of the site, which is the part of the host before its public suffix; the site written in one word; a segment of the path; on GitHub, Hugging Face, arXiv and npm only the path) or stands in its title (the first heading of the page as the page reader gives it); a name shorter than 3 letters and digits proves nothing. Code decides this. Only when code cannot decide, a low-cost AI model is asked whether the page is the page of the item with that name: it gets a fixed instruction, the name and at most 2,000 characters of the cleaned page text, and only the one word yes or no is taken from its answer. A no, any other answer and no answer give nothing. The model writes no text a user sees. A page on GitHub and a page on a host that is never read (UC-020 BR-002) are not read for the check: there the address alone decides. When the page of the item is found, its address joins the links of the entry and follows the rule of its kind (BR-006): the facts of the item come from that page or from its source, never from the directory. After 3 steps without such a page, the entry gives nothing. At most 100 steps are taken per day by default and at most 50 calls of the model are made per day by default; a read costs one live web credit of the Scout's daily limit. When a cap, the credits or the budget of the pass is spent, the links left wait for a later pass; so does a link whose status request a cap of BR-014 refused, and that request counts as no step. What following gave for each link (the address found or nothing, the number of steps, and whether code or the model decided) is kept with the last read of the list and used until the list is read again; a link is not followed again before that. With the switch off nothing is followed, and what following gave stays.

### BR-014: The Scout's Own Status Request

A status request is a request of the Scout's own server to a site, for one address. It is made for two things only: to follow a link that redirects (BR-013), and to ask whether a web page list whose read failed still exists (BR-012). It asks for the status only: with HEAD, and with GET when the site refuses HEAD, and then no body is read. The request follows no redirect: the address a redirect names is the next step of BR-013, and BR-012 does not use it. The content of a page never comes through it. The address must pass the Scout's host check (https, a public host name, no IP address, no private or reserved name, no punycode, no user info, port 443 only); its host must not be one never read (UC-020 BR-002) nor a shadow library; every address the host name resolves to must be public, and the request connects to those addresses only. No credential, no cookie and no referrer is sent, and the request names helper2.ai. The robots.txt of the site is honoured for the path asked and kept one day per host: a site with no robots.txt states no rule, and a robots.txt that cannot be read allows nothing. At most 200 requests are made per day and at most 30 to one host by default, for both uses together and the requests for robots.txt included; every try counts. The answer is the status code, which also says that a page is gone, and for a redirect the address it names.
