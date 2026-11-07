# 0062 — Writing about a resource: an article keeps its own facts and names what it is about

**Status.** Accepted. **Builds on:** [0019](0019-evidence-gated-enrichment.md), [0051](0051-tavily-discovery.md), [0056](0056-evidence-ladder.md), [0061](0061-linkedin-via-tavily.md) **Amended by:** [0064](../edge/0064-relationship-target-in-review.md) (an admin links, adds or renames the target in the review list).

## Context
Much of what the Scout finds is writing about other things: a news item about a new model, a blog post about a
product launch, a paper about a method. The enrichment did not know this. It asked the same questions of every
resource, as if it were a product.

So an article got the facts of what it talks about. An Engadget news item about "Gemini 4 Carbon" came back with
pricing FREEMIUM, audience BUILD_WITH_AI, use case coding, and type MODEL (refused, as a conflict with the source).
The pricing is Gemini's, not the article's. The type MODEL is not wrong either: the article is *about* a model. But
there was no place to say that.

Tavily discovery dropped the same writing on the way in ([0051](0051-tavily-discovery.md)): a blog post or a news
post about a product was neither a product site nor a list, so it was thrown away, with what it said about the
product.

## Decision
1. **Writing is ARTICLE, NEWS and PAPER** (`FieldsByType`). It keeps three fields: `audience` and `techLevel` (who it
   is for) and `useCase` (its topic). Pricing, licence, open source, platforms and type are facts of what it is
   about: the validator refuses them for writing ("not a fact of a NEWS, but of what it is about"), whatever the
   quote says. Every other type keeps every field.
2. **ABOUT links writing to its subject.** A new relationship kind, from the article to the model, product or tool it
   is mainly about. It follows the rules of every relationship ([0019](0019-evidence-gated-enrichment.md)):
   - the quote must name the target;
   - a target the catalog has by that exact title is linked and VERIFIED;
   - an unknown target stays UNVERIFIED and opens a `RELATIONSHIP_CANDIDATE` review item;
   - at most 3 per run (`MAX_ABOUT`): an article is mainly about a few things, not every name it drops;
   - ABOUT is only for writing, and writing has no other kind.
3. **The model is told the type.** Prompt `enrich-v5`: the user message has `Type: NEWS` next to the title, and the
   rules say what writing gives and that it names its subjects with ABOUT. The type comes from the catalog
   (`EnrichmentInput.type`), never from the model.
4. **Stored answers are cleaned by code.** The re-check of [0056](0056-evidence-ladder.md) runs once more after the
   deploy (marker `recheck-enrichment-enrich-v5`) and on the admin's button. It now also rejects every AI field
   that writing does not keep, whatever its verification. No model call: the rule is code. Writing gets its ABOUT
   links when it is next enriched (new or changed text, or an admin requeue), not by a rerun of the whole catalog.
5. **Discovery keeps posts about products as ARTICLE candidates** (`DiscoveryPages`, shape ARTICLE). A post is a page:
   - below a writing place (`/blog/…`, `/news/…`, `/posts/…`, `/insights/…`), or on a writing host (`blog.`,
     `news.`, Medium, Substack, TechCrunch …; the admin keeps the writing hosts and the directories,
     [0063](0063-discovery-hosts.md));
   - or with a post's slug (`/google-gemini-carbon-promises-significant-leap`).

   Not a post: a blog's index, an author's page (`medium.com/@someone`), docs, help, careers, legal and community
   pages, and directories (Product Hunt, G2, Futurepedia …). A post waits in the review list as a `RESOURCE_CANDIDATE`
   with type ARTICLE and its own URL; every discovery category declares ARTICLE, so it can be accepted. After that, its
   enrichment names what it is about.

## Consequences
- An article shows what it is about ("About Gemini 4 Carbon"), not the subject's price or licence.
- The Engadget item loses its pricing and type at the first re-check; its use case and audience stay.
- More review work: discovery now proposes posts too. Each one is a candidate once per URL, as before.
- An ABOUT target that is not in the catalog waits in review by name. The admin links it to a resource, adds the
  resource, or renames it ([0064](../edge/0064-relationship-target-in-review.md)).
- No change of cost: the type and two lines of rules add a few tokens per call, inside the same daily caps.
- No new personal data: the relationship holds a product or model name. The Privacy page does not change.
- AI Act Art. 50: an ABOUT link is AI-extracted like every relationship, and labelled the same way in the UI and the
  API (`origin`).

## Security and privacy (STRIDE)
- **Tampering / prompt injection**: an article can name any product to get a link. Mitigation: the quote must name
  the target, the target must already be in the catalog by exact title to be VERIFIED, at most 3 per run, the rest
  waits for a person.
- **Information disclosure**: none new; the target is a name the public article states.
- **Denial of service / cost**: no new calls; discovery posts are review items, capped per URL.
- **Elevation**: unchanged; only an admin accepts a candidate or a relationship.

## Alternatives considered
- **A `subject_id` column on the resource** — one subject only; a post often compares two models.
- **A separate mention table** — the same data as a relationship, with its own retention, API and review flow.
- **Let the model change the type** — the source states the type; a news item about a model is still news.
- **Create a resource for an unknown ABOUT target** — a resource needs its own URL, and the article gives only a
  name. The target waits in review by name; the post itself is the candidate, under its own URL.
- **Keep dropping posts in discovery** — they are often the first trace of a new product; the link to the subject is
  the value.

## Code
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/FieldsByType.kt` — writing types, their fields, ABOUT.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/EvidenceValidator.kt` — fields and kinds by type, the ABOUT cap.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/Enrichment.kt` (`EnrichmentInput.type`),
  `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/CatalogEnrichmentStore.java`.
- `backend/apps/backoffice/src/main/resources/prompts/scout/enrich-v5.txt`,
  `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/SpringAiEnrichmentModel.java`.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/RecheckEnrichments.kt`,
  `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/JdbcEnrichmentResults.java` (the stored type).
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/tavily/DiscoveryPages.java`, `TavilyDiscoveryCollector.java`.
- `backend/libs/schema/src/main/resources/db/migration/V16__relationship_about.sql`; `api/public.yaml` (kind ABOUT).
- Tests: `EvidenceValidatorWritingTest`, `RecheckEnrichmentsTest`, `SpringAiEnrichmentModelTest`,
  `DiscoveryPagesTest`, `TavilyDiscoveryCollectorTest`.
- Diagrams: [flow](../../diagrams/flow-0062-writing-about-a-resource.svg) · [sequence](../../diagrams/sequence-0062-writing-about-a-resource.svg).
