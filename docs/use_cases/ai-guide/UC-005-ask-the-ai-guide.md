# Use Case: Ask the AI Guide

## Overview

**Use Case ID:** UC-005  
**Use Case Name:** Ask the AI Guide  
**Primary Actor:** Visitor  
**Secondary Actors:** Language Model, Embedding Service, Source Provider  
**Goal:** Get a short answer to a need in plain words, built only from retrieved catalog results, with every claim backed by a quote that code has found word for word in the source  
**Trigger:** The visitor chooses "Ask the guide" (or Shift+Enter) for the words in the search box  
**Status:** Approved  

**Requirements:** [FR-007, FR-008, FR-009, NFR-002, NFR-004, NFR-006, NFR-007, NFR-008, NFR-010, NFR-013, NFR-015, C-001, C-003, C-005](../../requirements.md)  
**Mockup:** [Screens and states](../../mockups/uc-005-ask-the-ai-guide.md)  
**API:** [askGuideStream](../../../api/public.yaml#askGuideStream)

## Preconditions

- The search box holds the visitor's words.

## Main Success Scenario

1. Visitor asks the guide with a need in plain words, optionally with an audience path and the category filter active on the results page; the search results stay on the page.
2. System checks the request and gives the question a place among the questions running now.
3. System looks for a stored answer to the same question and finds none.
4. System reserves one of the visitor's AI answers of the day.
5. System asks the Language Model to read the question: what kind of answer is wanted (suggestions, how to do something, alternatives to a tool, a comparison, what something is, the newest, something to learn from, or when something happens), the names asked about, an English search query, up to 4 requirements and a period; System tells the visitor how it understood the question.
6. System searches the catalog and the listed sources with that query (UC-001) and takes the best 24 results as candidates, only tools unless the visitor asked for something to read.
7. System asks the Language Model to choose up to 5 candidates, best first, each with a reason and a quote from the candidate's own text.
8. System checks every proposed pick in code: the candidate was retrieved, the reason holds no link, and the quote is found word for word in the candidate's own title or description; it keeps at most 3 picks and checks each requirement against what the source states.
9. System asks the Language Model to check each kept reason against its quote and the candidate's text, and removes the picks whose claims are not supported or that do not fit the need.
10. System sends the answer: the picks with their reasons labelled as AI-written, their quotes with the page that holds them, the requirement checks, the steps the guide took, and the AI answers and web searches the visitor has left today.
11. System stores the answer for identical questions and counts the question against the visitor's AI answers of the day.

## Alternative Flows

### A1: Invalid Request

**Trigger:** The need is shorter than 3 or longer than 500 characters, or a category is unknown (step 2)  
**Flow:**

1. System refuses the question and says what is wrong.
2. Use case ends.

### A2: Too Many Questions

**Trigger:** The visitor has reached the guide rate limit (step 2)  
**Flow:**

1. System refuses the question and says when the visitor can ask again.
2. Use case ends.

### A3: Guide Busy

**Trigger:** The visitor already has the most questions running at once, or the guide has no free place for a new question (step 2)  
**Flow:**

1. System says the guide is busy; the search results stay and the visitor can try again.
2. Use case ends.

### A4: Answer Already Known

**Trigger:** A stored answer to the same question exists, or the same question is being answered for someone else right now (step 3)  
**Flow:**

1. System sends the stored answer at once, or waits for the answer in progress and shares it.
2. System counts no AI answer for the visitor.
3. Use case ends.

### A5: Daily AI Answers Used Up

**Trigger:** The visitor, or the visitor's network, has used today's AI answers (step 4)  
**Flow:**

1. System searches without the Language Model and shows the top search results.
2. System says the AI answers of the visitor or of the network are used up and that they are back after 00:00 UTC.
3. Use case ends.

### A6: Question Needs Clarification

**Trigger:** The question is too open to answer well (step 5)  
**Flow:**

1. System asks the visitor a question back, with options to choose from.
2. System counts no AI answer for the visitor.
3. Visitor chooses an option, writes an answer, or chooses to search anyway.
4. Use case continues at step 2.

### A7: Similar Question Answered Before

**Trigger:** Similar-question reuse is on, and a stored answer exists for a question that is close in meaning and was read the same way (step 5)  
**Flow:**

1. System sends that answer, in the names and requirements the visitor used.
2. System counts the question against the visitor's AI answers of the day.
3. Use case ends.

### A8: Comparison, Explanation or Date Question

**Trigger:** The question compares named tools, asks what something is, or asks when something happens (step 7)  
**Flow:**

1. System searches each named subject on its own, or the dated results for the period.
2. For a comparison, System asks the Language Model for a table of aspects with one quoted cell per subject; for an explanation, for a few sentences, each with a quote; a date question is answered from the dated results without the Language Model.
3. System checks every cell and sentence with the same quote rule as a pick and drops those that fail.
4. Use case continues at step 9.

### A9: No Candidates or Subject Not Found

**Trigger:** The search finds no candidate, or a named subject is not found (step 6)  
**Flow:**

1. System says so and shows what the search found.
2. System counts no AI answer for the visitor.
3. Use case ends.

### A10: Nothing Fits

**Trigger:** No proposed pick passes the checks (step 8)  
**Flow:**

1. System shows the top 3 search results marked as having no AI reasoning, says why in one line, and offers to ask again without each requirement it read.
2. System counts no AI answer for the visitor.
3. Use case ends.

### A11: Model Unavailable or Daily Budget Spent

**Trigger:** No model is configured, a model call fails, or the guide's model calls of the day are spent (step 5)  
**Flow:**

1. System uses the visitor's own words as the search query and shows the top 3 search results, with a notice when the budget is spent that says when AI answers are back.
2. System counts no AI answer for the visitor.
3. Use case ends.

### A12: Out of Time

**Trigger:** The question reaches its deadline (step 9)  
**Flow:**

1. System answers with what it has: the kept picks marked as not checked, or the top search results with a notice that the guide ran out of time.
2. When the search had not finished, System says the guide took too long and the visitor can try again.
3. The answer is not stored.
4. Use case ends.

### A13: Guide Fails

**Trigger:** An unexpected error stops the question (step 6)  
**Flow:**

1. System says the guide could not answer and the visitor can try again in a moment; the search results stay.
2. System counts no AI answer for the visitor.
3. Use case ends.

## Postconditions

### Success Postconditions

- The visitor sees an answer whose every AI-written text is labelled on the page and marked in the answer.
- Every quote shown was found word for word in the source text of its resource.
- A complete answer is stored for identical questions; with similar-question reuse on, the meaning of the question is indexed until the stored answer expires.
- An answer with AI content counts against the visitor's and the network's AI answers of the day.

### Failure Postconditions

- An answer without AI content, a failed question and a shared answer cost the visitor no AI answer.
- An incomplete answer (out of time or budget spent) is never stored.
- No pick, cell or sentence whose quote was not found in the source is ever shown.

## Business Rules

### BR-001: Request Limits

A need holds 3 to 500 characters. An answer to a question back holds at most 300 characters. A request removes at most 8 parts of how the guide read it, and names at most 40 categories, each a known category or "unknown"; an unknown category is refused. Removed parts and categories are applied by code and never sent to a model.

### BR-002: Guide Rate Limit

A visitor asks at most 10 questions a minute by default, counted per network address. The limit is shared with the setup guide of UC-006.

### BR-003: Questions at Once

A visitor has at most 2 questions running at once per server, and a server answers at most 8 questions at once, by default. A question that finds no free place is refused at once as busy. A stored or shared answer counts among the visitor's 2 questions while it is served, and takes none of the server's 8 places.

### BR-004: Deadline

A question has 90 seconds by default. Each model call gets at most 40 seconds by default, never runs past the deadline less a margin of 2 seconds, and does not start with less than 10 seconds left. The deadline must leave room for two model calls.

### BR-005: Daily AI Answers per Visitor and Network

A visitor gets 30 AI answers per UTC day and a network 300 per UTC day, by default; a question needs room in both. An answer without AI content (no pick, no comparison row, no explanation sentence), a question back, a failed question and a shared answer are given back, at most 5 a day per visitor by default. Stored and shared answers do not count. The visitor is counted under a daily pseudonym of the network address, never under the address itself.

### BR-006: Daily Model Budget

The guide makes at most 600 model calls a day by default, for all visitors together, spread over 12 slots of the day so that the budget is not spent early. When the budget of the day or of the current slot is spent, the guide answers as in A11 and says when AI answers are back: at the start of the next slot, or after 00:00 UTC.

### BR-007: Quote Rule

A quote is at most 300 characters and is found word for word in the candidate's own title or description, ignoring case and spacing, starting and ending on whole words, with at least 40 characters and 6 words, or the whole text when it is shorter. An AI-written description is never a text a quote can come from.

### BR-008: Reason Rule

A reason is not blank, holds at most 400 characters and holds no link or web address in any disguise. A comparison cell holds at most 200 characters and an explanation sentence at most 300.

### BR-009: Picks

Only the first 5 proposals are read and at most 3 picks are shown. A pick must be a retrieved candidate, not a duplicate, and not a thing to read when none was asked for.

### BR-010: Requirement Checks

The guide reads at most 4 requirements. A licence requirement is decided by the licence the source states, matched as a whole word; "open source" needs the source's open-source flag; another requirement needs a quote of at least 5 characters found word for word in the source. The model can only lose a check, never create one.

### BR-011: Reason Check

After the code checks, the Language Model checks every kept reason, comparison cell and explanation sentence against its evidence; a claim it does not support, or a pick that does not fit the need, is removed. When the check cannot run in time, the pick is marked as not checked. An optional second model may grade the check for monitoring only; it never changes the answer and is off unless a judge model is configured.

### BR-012: Stored Answers

A complete answer is stored for 24 hours by default, for 6 hours when it concerns a period, until the next UTC midnight for a date question, for 10 minutes when it holds no AI content, and never longer than its web results stay fresh. An answer is not stored when the model was not used, when it asks back, when a claim was not checked, or when the web search was skipped because of the daily limit.

### BR-013: Similar Questions

Reuse of the answer of a similar question is off by default. When a similarity threshold is set, the Embedding Service turns the question into a vector of 1024 numbers, and a stored answer is reused when its question was read the same way and is at least that close in meaning. Indexed questions expire with their answer and are deleted by a daily job.

### BR-014: AI-Written Text Marked

Every AI-written text of the answer (the planned search query and requirements, each reason, comparison cell and explanation sentence, and a question back) is labelled as AI-written on the page and marked in the answer with the provider, the models and the fields they wrote.

### BR-015: Third-Party Text Is Data

The need, the visitor's answers and every candidate text are passed to the model as data inside marked blocks; nothing they say is followed as an instruction, and only the picks of the model's answer reach the page.

### BR-016: Candidates

The guide considers at most 24 candidates by default and searches only tool types unless the visitor asks for something to read.

### BR-017: Web Searches

The guide's searches use the guide's own daily web searches of UC-001 BR-008; a stored, shared or reused answer spends none.

### BR-018: Shadow Libraries

A stored answer that cites a shadow library is not served and the question is answered again, as UC-004 BR-006 states.

### BR-019: Asked Only by Choice

The guide answers only when the visitor asks it. A search never asks the guide, whatever the length of its words.

### BR-020: One Question at a Time on the Page

A new question replaces the question being answered. New words in the search box drop the question and its picks, because the picks answered the earlier words.

### BR-021: Question Nobody Waits For

When the live stream of the model's reasoning is on (off by default), a question is stopped 3 seconds after the last visitor waiting for its answer has left. It gives no answer and costs no AI answer.

### BR-022: Answer Sizes

A comparison names at most 3 subjects and holds at most 8 aspects. An explanation holds at most 4 sentences. A date answer lists at most 5 events.
