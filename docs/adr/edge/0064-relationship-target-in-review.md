# 0064 — Relationship target in the review list: link, add or rename

**Status.** Accepted. **Builds on:** [0019](../scout/0019-evidence-gated-enrichment.md), [0023](0023-identity-and-admin.md), [0038](../guide/0038-ai-content-marking.md), [0045](0045-back-office.md), [0062](../scout/0062-writing-about-a-resource.md)

## Context
A relationship candidate names its target as the model wrote it: "Gemini 4 Carbon", "LangChain". When the catalog
has no resource with exactly that title, the link has no target. The admin could only accept or reject it: accepted,
it stayed a name with no page behind it.

Often the right resource is in the catalog under another title ("LangChain framework"). Sometimes it is not in the
catalog at all: a new model the article is about.

## Decision
1. **One call changes the target** of an open relationship candidate:
   `PUT /admin/review-items/{id}/relationship-target`, with exactly one of:
   - `resourceId`: a resource in the catalog; the relationship takes its title;
   - `newResource`: name, official https address and type (a tool or a model: PRODUCT, MODEL, AGENT, PROMPT, SKILL,
     MCP_SERVER, LIBRARY, DATASET); see 2;
   - `title`: another name, with no resource.

   It answers the item's evidence with the new target. The item stays open: accepting it is what makes the link
   VERIFIED ([0019](../scout/0019-evidence-gated-enrichment.md)), so a change can be checked before it counts.
2. **Adding a resource** (`AddCuratedResource`). An address the catalog already has, as a resource or as one of its
   records, gives that resource: never a second one. Otherwise it becomes a record of the `curated` source, with the
   admin's name and type, no description, data origin CURATED and enrichment NOT_RUN: the next Scout run enriches it
   from its own pages ([0056](../scout/0056-evidence-ladder.md)). The curated source now lists these types too.
3. **The target is the admin's.** The relationship's origin becomes CURATED. The kind is still the model's: the
   review evidence marks `relationship.kind` as AI-written, not `relationship.target`
   ([0038](../guide/0038-ai-content-marking.md)). On the resource page a CURATED relationship carries no AI label.
4. **Refusals change nothing.** One transaction, with the item row locked: 404 for no item; 409 `already-decided`
   for an item no longer open; 409 `not-applicable` for an item that is not a relationship candidate; 409
   `duplicate` when the resource already has a relationship of that kind to that target; 400 for two or none of the
   three, an unknown resource, the resource itself, a non-https address, or a type that is not a tool or a model. A
   resource added for a refused change is rolled back with it.
5. **Audit.** `CHANGE_RELATIONSHIP_TARGET` with the item id and `LINKED <resource id>` or `RENAMED`, and
   `ADD_RESOURCE` with the new resource's id and `ADDED`. Refused changes are not recorded: nothing changed.
6. **Back office.** The evidence of an open relationship candidate has "Change the target": find the resource with
   the visitor search (as the home examples do), add it, or give another name. Errors say why; the form stays open.

## Consequences
- An ABOUT link ([0062](../scout/0062-writing-about-a-resource.md)) can always end on a real page, even for a model
  launched yesterday.
- A resource an admin adds starts with only a name and an address. Until the Scout enriches it, its card is thin.
- Two writes for one decision: change the target, then accept. The UI keeps them next to each other.

## Security and privacy (STRIDE)
- **Spoofing / elevation**: ADMIN only, on the admin host behind the VPN, with CSRF
  ([0035](0035-admin-host.md)); the same rules as every back-office write.
- **Tampering**: the address must be https and canonical; the name and the type are checked; a link to itself is
  refused. The resource is added through the same catalog write as every record.
- **Repudiation**: every change and every added resource is in the append-only audit trail.
- **Information disclosure**: none new. A resource holds a public name and address; the audit holds the
  administrator's GitHub id, as for every action. The Privacy page already names these records.
- **Denial of service**: one admin, one row per change; no outbound call (the page is read later by the Scout,
  inside its caps).

## Alternatives considered
- **Pick the target when accepting** — one call, but accept then does two things, and a wrong target is VERIFIED at
  once with no second look.
- **Add a resource from its own admin page** — a second place to keep, and the admin leaves the item to do it.
- **Let a name-only target create a resource later** — no address, so no page to enrich and no way to avoid a
  duplicate. Every new resource needs its address.

## Code
- `backend/src/main/java/ai/helper2/catalog/usecase/ChangeRelationshipTarget.java`, `AddCuratedResource.java`.
- `backend/src/main/java/ai/helper2/catalog/internal/RelationshipTargets.java`, `CuratedTypes.java`,
  `ResourceStore.java` (`atUrl`), `CatalogConfiguration.java` (the curated source's types).
- `backend/src/main/java/ai/helper2/admin/web/ReviewController.java`; `api/openapi.yaml`
  (`RelationshipTargetChange`, `NewResource`, audit actions).
- `backend/src/main/resources/db/migration/V19__relationship_target.sql`.
- `frontend/src/pages/admin/RelationshipTarget.tsx`, `ReviewEvidence.tsx`.
- Tests: `RelationshipTargetApiIT`, `RelationshipTarget.test.tsx`, `ReviewEvidence.test.tsx`.
- Diagrams: [flow](../../diagrams/flow-0064-relationship-target-in-review.svg) · [sequence](../../diagrams/sequence-0064-relationship-target-in-review.svg).
