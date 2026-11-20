# Use Case: Support helper2.ai

## Overview

**Use Case ID:** UC-016  
**Use Case Name:** Support helper2.ai  
**Primary Actor:** Visitor  
**Secondary Actors:** Payment Provider  
**Goal:** Give money to help run helper2.ai through a payment provider's own page, while the site shows support, analytics and ads only where they are switched on  
**Trigger:** The visitor opens the support page from the footer  
**Status:** Draft  

**Requirements:** [FR-022, FR-023, C-007](../../requirements.md)  
**API:** [getSite](../../../api/public.yaml#getSite)

## Preconditions

- _None — the support page is public._

## Main Success Scenario

1. Visitor opens the support page.
2. System reads the site-wide settings and shows what running helper2.ai costs and the ways to give that are set up: sponsoring on GitHub and giving by card.
3. Visitor chooses a way to give.
4. Payment Provider takes the gift on its own page.
5. Payment Provider returns the visitor who gave by card to the thank-you page of helper2.ai.
6. System thanks the visitor and links back to search.

## Alternative Flows

### A1: No Way to Give Is Set Up

**Trigger:** Neither GitHub sponsoring nor giving by card is set up (step 2)  
**Flow:**

1. System says that giving is not open yet.
2. Use case ends.

### A2: Sponsoring on GitHub

**Trigger:** The visitor chooses to sponsor on GitHub (step 3)  
**Flow:**

1. System opens the GitHub sponsoring page in a new tab, and the visitor stays on helper2.ai.
2. Use case ends.

## Postconditions

### Success Postconditions

- The gift is taken by the payment provider; helper2.ai stores nothing about it.

### Failure Postconditions

- Nothing changes on helper2.ai.

## Business Rules

### BR-001: Ways to Give

A way to give is offered only when its address is configured: a GitHub Sponsors page (`H2_SUPPORT_GITHUB_SPONSORS_URL`) or a Stripe payment page (`H2_SUPPORT_STRIPE_PAYMENT_URL`). Both go to the provider's own page, and giving changes nothing on helper2.ai.

### BR-002: Analytics Question

The site asks whether a visit may be counted with Google Analytics only when a measurement identifier is configured (`H2_ANALYTICS_GA4_MEASUREMENT_ID`). The answer is kept in the browser and can be changed from the footer.

### BR-003: Ads

An ad is shown only while the Google ads switch is on (UC-028 BR-001) and an ad unit is configured for its place: the resource page, the search results or the guide answer. While ads are shown, that is while the switch is on and an ad client and at least one ad unit are configured, Google's own consent tool asks for ads and analytics in place of the site's question; with the switch off, the site asks its own analytics question again. The site's ads declaration file stays available when the switch is off.

### BR-004: Configured Values

A configured GitHub Sponsors page, Stripe payment page, measurement identifier, ad client or ad unit must have the expected form, or the application does not start.
