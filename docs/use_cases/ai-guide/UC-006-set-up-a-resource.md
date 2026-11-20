# Use Case: Set Up a Resource

## Overview

**Use Case ID:** UC-006  
**Use Case Name:** Set Up a Resource  
**Primary Actor:** Visitor  
**Secondary Actors:** Language Model, Source Provider  
**Goal:** Install, configure and connect a resource to the visitor's AI tools, with setup facts and steps taken only from the resource's own source data and documentation  
**Trigger:** The visitor opens the setup part of a resource page, or asks for its step-by-step guide  
**Status:** Draft  

**Requirements:** [FR-011, NFR-010, C-005](../../requirements.md)  
**API:** [getResourceSetup](../../../api/public.yaml#getResourceSetup), [askResourceGuide](../../../api/public.yaml#askResourceGuide)

## Preconditions

- The resource is in the catalog.

## Main Success Scenario

1. Visitor opens the setup part of a resource page.
2. System finds the resource in the catalog.
3. System reads the resource's latest entry in the Official MCP Registry and keeps the ways to run it that the rules accept: a hosted server, a local package, or a local command.
4. System writes, for each AI client and each way to run, the configuration in the format that client's own documentation states, with placeholders for secret values, and names the source of the facts and when it was read.
5. Visitor asks for the step-by-step guide.
6. System reads the resource's own documents (its GitHub README, its npm package README, its Hugging Face model or Space card) from fixed hosts.
7. System looks for a guide already written from the same documents, finds none, and takes one of the visitor's setup guides of the day.
8. System asks the Language Model to pick and order passages of the documents into sections (requirements, install, configure, use, connect), each step with a quote and an optional command.
9. System keeps a step only when its quote is found in the cited document and its command is whole lines of one code block, or one inline code span, of that document, exactly as written.
10. System asks the Language Model to confirm each step's own text against its quote, and shows a step's text only when it is confirmed.
11. System sends the guide with its documents and when they were read; the step texts are labelled as AI-written, and System stores the guide for every visitor.

## Alternative Flows

### A1: Resource Not Found

**Trigger:** The resource is not in the catalog, or it is on a shadow library (step 2)  
**Flow:**

1. System says the resource was not found.
2. Use case ends.

### A2: Configuration from the README

**Trigger:** The resource is an MCP server with no usable registry entry, and it has a GitHub repository or an npm package (step 3)  
**Flow:**

1. System reads the server configurations its README states and keeps the accepted ways to run it.
2. Use case continues at step 4.

### A3: No Setup Data

**Trigger:** No source states setup data for the resource (step 3)  
**Flow:**

1. System shows no configuration and says that the sources state none.
2. Use case continues at step 5.

### A4: Registry Unavailable

**Trigger:** The registry cannot be read and the README gives no configuration (step 3)  
**Flow:**

1. System says the setup data could not be read now and the visitor can try again later.
2. Use case continues at step 5.

### A5: No Documents

**Trigger:** The resource has no readable document on the fixed hosts (step 6)  
**Flow:**

1. System says there is nothing to write a guide from.
2. Use case ends.

### A6: Guide Already Written

**Trigger:** A guide was written from the same documents before (step 7)  
**Flow:**

1. System sends the stored guide without counting it against the visitor.
2. Use case ends.

### A7: Guide Cannot Be Written Now

**Trigger:** No model is configured, the visitor has used today's setup guides, or every place for writing a guide is taken (step 7)  
**Flow:**

1. System sends the guide without steps and says why, with when to try again when the visitor's guides of the day are used up.
2. Use case ends.

### A8: Model Fails or Budget Spent

**Trigger:** The model call fails, its answer cannot be read, or the setup guide's model calls of the day are spent (step 8)  
**Flow:**

1. System sends the guide without steps, says why, and gives the time to try again when the budget is spent.
2. System gives the visitor's setup guide back.
3. Use case ends.

### A9: No Step Survives

**Trigger:** No proposed step passes the checks (step 9)  
**Flow:**

1. System sends the guide without steps and says so.
2. Use case continues at step 11.

### A10: Step Texts Not Confirmed

**Trigger:** The confirmation call fails (step 10)  
**Flow:**

1. System shows the steps with their quotes and commands only, without their own text, and does not store the guide.
2. Use case continues at step 11.

## Postconditions

### Success Postconditions

- The visitor sees configurations and steps taken only from the resource's own data and documents, each step with its quote and document.
- A complete guide is stored and served to every visitor without counting.
- A written guide counts against the visitor's and the network's setup guides of the day.

### Failure Postconditions

- No step, command or configuration is shown that the sources do not state.
- A guide that was not written costs the visitor no setup guide.

## Business Rules

### BR-001: Facts Only from the Sources

Setup facts come only from the resource's own source records, read when asked for. Nothing is invented: a client that cannot use a way to run is listed with the reason instead of a configuration, and a secret value is always a placeholder named after its setting.

### BR-002: Daily Setup Guides

A visitor gets 20 written setup guides a day and a network 200 a day, by default. The setup guide makes at most 500 model calls a day for all visitors together by default. A guide that is not written gives the visitor's turn back.

### BR-003: Guides Written at Once

A server writes at most 2 setup guides at once by default; a request that finds no free place is not kept waiting.

### BR-004: Reading Documents

Documents are read only from fixed hosts, with a time limit of 10 seconds per document, are kept for 1 hour in a cache of 32 MB, and at most 1000 documents are fetched an hour, by default.

### BR-005: Step Checks

A section holds at most 8 steps. A step's text holds at most 280 characters and no link outside code. A quote holds at most 500 characters and at least 30 characters and 5 words, and is found in the cited document. A command holds at most 40 lines and is whole lines of one code block, or one inline code span, of the cited document, exactly as written.

### BR-006: Step Texts

By default a step's own text is shown only when the Language Model confirms it against its quote; otherwise only the quote and the command are shown.

### BR-007: Stored Guides

A written guide is stored for 7 days by default and is the same for every visitor. A model answer that cannot be read is stored for 10 minutes by default, so it is not asked again at once. A guide is tied to the documents it was written from: a changed document leads to a new guide.

### BR-008: AI-Written Text Marked

The step texts and the language named for a command are labelled as AI-written on the page and marked in the answer with the provider, the model and the fields it wrote.

### BR-009: Rate Limits

Setup data is read at most 120 times a minute per visitor by default. The step-by-step guide shares the guide rate limit of UC-005 BR-002.

### BR-010: Shadow Libraries

A document on a shadow library is never read, as UC-004 BR-006 states.
