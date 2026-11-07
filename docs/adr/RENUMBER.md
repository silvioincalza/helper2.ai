# ADR renumbering (first-release reset, PR 1a)

The first release keeps 37 ADRs instead of 70. Each old ADR moves once, to the number of the record it ends up in.
This file is the mapping. `scripts/adr-renumber.py` reads it and rewrites the repository in one pass;
`scripts/adr-check.py` checks in CI that every ADR reference points at a record that exists.

- Every number from 0001 to 0037 is used. 0021 is a number again: it never existed before.
- A merged record is, for now, the old records one after the other, each under `## Part: was ADR NNNN`. Its context
  PR of the reset writes the merged text (the column "Texts merged in"); PR 13 does the ones no context PR owns.
- 0038 ("First release reset") is not created here. PR 13 writes it, or makes it a section of 0034.
- The applied migrations (`backend/libs/schema/src/main/resources/db/migration/V*.sql`) kept the old numbers in their
  comments: changing them changes their Flyway checksums. PR 1b replaced them with one `V1__schema.sql` that cites the
  new numbers, and `adr-check.py` checks it like any file. `adr-renumber.py` still skips V*.sql, so the check below,
  run on the tree before 1a, gives the 1a change. The repeatable seeds (`R__*.sql`) are rewritten: they are
  idempotent, and a changed one only runs again.
- The Postgres image's build files (`deploy/postgres/`) keep their old numbers too: any change there needs a new
  `postgres.image.tag` (CI's postgres-tag check), so a rebuild, a new digest and a signature, for a comment. They move
  with the next real Postgres tag.
- `ADR 0026 (part was 0045) §7` is a section of an old record inside a merged one. Inside a merged record,
  "the part that was ADR 0045" points at its own part.
- Only for `next/3.0`. On `main` the changed comments of the seeds would make staging and prod run them again, and
  nothing else here belongs on `main` before the reset.

## Check it again

The script refuses a tree that is already renumbered. To review the change, run it on a fresh copy of the base and
compare. Three steps make the whole change: the script, the formatter (some comments got longer, so
google-java-format and ktfmt wrap them again), and the diagrams.

```bash
git worktree add --detach ../renumber-check origin/next/3.0
for f in docs/adr/RENUMBER.md scripts/adr-renumber.py scripts/adr-check.py; do
  git show HEAD:$f > ../renumber-check/$f
done
cd ../renumber-check
python3 scripts/adr-renumber.py           # prints every section reference and bare number it moved
(cd backend && ./gradlew spotlessApply)
docs/diagrams/render.sh
git add -A && git diff --cached --stat <the 1a commit>
```

The only file it lists is `.github/workflows/ci.yml`: the `ADR references` step of the `changes` job, written by
hand.

## New records

| New | Category | File | Title | Merges old | Texts merged in |
|---|---|---|---|---|---|
| 0002 | foundation | `0002-modular-monolith-libraries-and-apps.md` | Modules and libraries: a modular monolith, hexagonal where the rules live, built as libraries and apps | 0002, 0068 | 3a |
| 0004 | foundation | `0004-persistence-schema-and-app-roles.md` | Persistence and the schema: PostgreSQL as system of record, provenance, migrations, a database role per app | 0004, 0070 | 1b |
| 0006 | foundation | `0006-outbound-calls-and-resilience.md` | Outbound calls: the HTTP client and the resilience policies | 0006, 0033 | 13 |
| 0007 | foundation | `0007-configuration-secrets-and-settings.md` | Configuration: H2_ variables, secrets in OpenBao, runtime settings | 0007, 0060 | 13 |
| 0010 | search | `0010-catalog-full-text-index.md` | Catalog full-text index: languages and the words of awesome lists | 0011, 0055 | 3b |
| 0013 | search | `0013-filters-facets-and-categories.md` | Filters, facets, categories and works-with flags | 0010, 0013, 0053 | 3b |
| 0016 | guide | `0016-guide-pipeline.md` | Guide pipeline: intents, plan, retrieve, choose, evidence gate, reason check | 0015, 0016, 0032 | 6 |
| 0017 | guide | `0017-guide-cost-guard-and-answer-cache.md` | Guide cost guard and answer cache | 0017, 0041 | 6 |
| 0018 | guide | `0018-ai-content-marking-and-research-trail.md` | Marking AI-written text and the research trail | 0038, 0044 | 6 |
| 0021 | scout | `0021-collectors-and-events.md` | Scout collectors and events, LinkedIn and the live web events scout included | 0020, 0022, 0061, 0067 | 5 |
| 0022 | scout | `0022-discovery.md` | Discovery: one collector per category, deep search in awesome lists, discovery hosts | 0051, 0058, 0063 | 5 |
| 0023 | scout | `0023-evidence-gated-enrichment.md` | Evidence-gated enrichment: the gate, the gap round, the evidence ladder, writing about a resource | 0019, 0043, 0056, 0062 | 5 |
| 0024 | edge | `0024-identity-and-admin-host.md` | Identity and the admin host | 0023, 0035 | 2 |
| 0026 | edge | `0026-back-office-and-review.md` | Back office and the review actions | 0045, 0064, 0065, 0066 | 9 |
| 0027 | frontend | `0027-frontend-one-box.md` | Frontend: one box, state in the URL, recent-answers cache | 0025, 0039 | 12 |
| 0028 | frontend | `0028-design-system.md` | Design system | 0040, 0042 | 12 |
| 0029 | frontend | `0029-site-pages-and-policy.md` | Site: pages for crawlers and the policy endpoint | 0050, 0052 | 10 |
| 0030 | frontend | `0030-monetisation.md` | Monetisation: gifts, analytics after consent, EthicalAds and AdSense | 0057, 0059 | 10 |
| 0032 | operations | `0032-runtime-topology.md` | Runtime topology: the Helm chart and three deployables | 0027, 0069 | 11 |
| 0033 | operations | `0033-ci-cd-and-supply-chain.md` | CI/CD and supply chain: per component, images signed and deployed by digest | 0028, 0046, 0071 | 13 |
| 0034 | operations | `0034-releases-and-versions.md` | Releases and versions: tags and release branches | 0029, 0048 | 13 |
| 0036 | quality | `0036-evaluation.md` | Evaluation: the guide and search | 0030, 0036 | 13 |
| 0037 | quality | `0037-code-format-and-dead-code-audit.md` | Code format and dead-code audit | 0047, 0054 | 13 |

"Texts merged in" names the PR of the reset that writes the merged text. To confirm with the PR plan.

## Old to new

| Old | Old path | New | New category | New file | Merged with | Note |
|---|---|---|---|---|---|---|
| 0001 | `docs/adr/foundation/0001-stack-and-runtime.md` | 0001 | foundation | `0001-stack-and-runtime.md` | — |  |
| 0002 | `docs/adr/foundation/0002-modular-monolith-hexagonal.md` | 0002 | foundation | `0002-modular-monolith-libraries-and-apps.md` | 0068 |  |
| 0003 | `docs/adr/foundation/0003-contract-first-rest-api.md` | 0003 | foundation | `0003-contract-first-rest-api.md` | — |  |
| 0004 | `docs/adr/foundation/0004-persistence-and-provenance.md` | 0004 | foundation | `0004-persistence-schema-and-app-roles.md` | 0070 |  |
| 0005 | `docs/adr/foundation/0005-redis-disposable-cache.md` | 0005 | foundation | `0005-redis-disposable-cache.md` | — |  |
| 0006 | `docs/adr/foundation/0006-outbound-http.md` | 0006 | foundation | `0006-outbound-calls-and-resilience.md` | 0033 |  |
| 0007 | `docs/adr/foundation/0007-configuration-and-secrets.md` | 0007 | foundation | `0007-configuration-secrets-and-settings.md` | 0060 |  |
| 0008 | `docs/adr/search/0008-federated-search.md` | 0009 | search | `0009-federated-search.md` | — |  |
| 0009 | `docs/adr/search/0009-ranking.md` | 0012 | search | `0012-ranking.md` | — |  |
| 0010 | `docs/adr/search/0010-filters-facets-dates-timeline.md` | 0013 | search | `0013-filters-facets-and-categories.md` | 0013, 0053 |  |
| 0011 | `docs/adr/search/0011-language-aware-search.md` | 0010 | search | `0010-catalog-full-text-index.md` | 0055 |  |
| 0012 | `docs/adr/search/0012-vector-search-pgvector.md` | 0011 | search | `0011-vector-search-pgvector.md` | — |  |
| 0013 | `docs/adr/search/0013-works-with-flags.md` | 0013 | search | `0013-filters-facets-and-categories.md` | 0010, 0053 |  |
| 0014 | `docs/adr/guide/0014-llm-integration.md` | 0008 | foundation | `0008-llm-integration.md` | — |  |
| 0015 | `docs/adr/guide/0015-ai-guide-evidence-gate.md` | 0016 | guide | `0016-guide-pipeline.md` | 0016, 0032 |  |
| 0016 | `docs/adr/guide/0016-guide-reason-check.md` | 0016 | guide | `0016-guide-pipeline.md` | 0015, 0032 |  |
| 0017 | `docs/adr/guide/0017-guide-cost-guard.md` | 0017 | guide | `0017-guide-cost-guard-and-answer-cache.md` | 0041 |  |
| 0018 | `docs/adr/scout/0018-scout-runner.md` | 0020 | scout | `0020-scout-runner.md` | — |  |
| 0019 | `docs/adr/scout/0019-evidence-gated-enrichment.md` | 0023 | scout | `0023-evidence-gated-enrichment.md` | 0043, 0056, 0062 |  |
| 0020 | `docs/adr/scout/0020-scout-collectors.md` | 0021 | scout | `0021-collectors-and-events.md` | 0022, 0061, 0067 |  |
| 0022 | `docs/adr/scout/0022-next-events.md` | 0021 | scout | `0021-collectors-and-events.md` | 0020, 0061, 0067 |  |
| 0023 | `docs/adr/edge/0023-identity-and-admin.md` | 0024 | edge | `0024-identity-and-admin-host.md` | 0035 |  |
| 0024 | `docs/adr/edge/0024-http-edge-protection.md` | 0025 | edge | `0025-http-edge-protection.md` | — |  |
| 0025 | `docs/adr/frontend/0025-frontend-one-box.md` | 0027 | frontend | `0027-frontend-one-box.md` | 0039 |  |
| 0026 | `docs/adr/operations/0026-observability.md` | 0031 | operations | `0031-observability.md` | — |  |
| 0027 | `docs/adr/operations/0027-runtime-topology.md` | 0032 | operations | `0032-runtime-topology.md` | 0069 |  |
| 0028 | `docs/adr/operations/0028-ci-cd.md` | 0033 | operations | `0033-ci-cd-and-supply-chain.md` | 0046, 0071 |  |
| 0029 | `docs/adr/operations/0029-release-versioning.md` | 0034 | operations | `0034-releases-and-versions.md` | 0048 |  |
| 0030 | `docs/adr/quality/0030-guide-evaluation.md` | 0036 | quality | `0036-evaluation.md` | 0036 |  |
| 0031 | `docs/adr/guide/0031-setup-guide.md` | 0019 | guide | `0019-setup-guide.md` | — |  |
| 0032 | `docs/adr/guide/0032-guide-intents.md` | 0016 | guide | `0016-guide-pipeline.md` | 0015, 0016 |  |
| 0033 | `docs/adr/foundation/0033-resilience-policies.md` | 0006 | foundation | `0006-outbound-calls-and-resilience.md` | 0006 |  |
| 0034 | `docs/adr/operations/0034-data-retention.md` | 0035 | operations | `0035-data-retention.md` | — |  |
| 0035 | `docs/adr/edge/0035-admin-host.md` | 0024 | edge | `0024-identity-and-admin-host.md` | 0023 |  |
| 0036 | `docs/adr/quality/0036-search-evaluation-harness.md` | 0036 | quality | `0036-evaluation.md` | 0030 |  |
| 0037 | `docs/adr/search/0037-tavily-store-and-daily-quota.md` | 0014 | search | `0014-tavily-store-and-daily-quota.md` | — |  |
| 0038 | `docs/adr/guide/0038-ai-content-marking.md` | 0018 | guide | `0018-ai-content-marking-and-research-trail.md` | 0044 |  |
| 0039 | `docs/adr/frontend/0039-frontend-recent-answers-cache.md` | 0027 | frontend | `0027-frontend-one-box.md` | 0025 |  |
| 0040 | `docs/adr/frontend/0040-frontend-design.md` | 0028 | frontend | `0028-design-system.md` | 0042 |  |
| 0041 | `docs/adr/guide/0041-guide-answer-cache.md` | 0017 | guide | `0017-guide-cost-guard-and-answer-cache.md` | 0017 |  |
| 0042 | `docs/adr/frontend/0042-redesign-design-system-v4.md` | 0028 | frontend | `0028-design-system.md` | 0040 |  |
| 0043 | `docs/adr/scout/0043-enrichment-gap-round.md` | 0023 | scout | `0023-evidence-gated-enrichment.md` | 0019, 0056, 0062 |  |
| 0044 | `docs/adr/guide/0044-research-trail.md` | 0018 | guide | `0018-ai-content-marking-and-research-trail.md` | 0038 |  |
| 0045 | `docs/adr/edge/0045-back-office.md` | 0026 | edge | `0026-back-office-and-review.md` | 0064, 0065, 0066 |  |
| 0046 | `docs/adr/operations/0046-supply-chain.md` | 0033 | operations | `0033-ci-cd-and-supply-chain.md` | 0028, 0071 |  |
| 0047 | `docs/adr/quality/0047-code-format.md` | 0037 | quality | `0037-code-format-and-dead-code-audit.md` | 0054 |  |
| 0048 | `docs/adr/operations/0048-release-branches-and-version-tags.md` | 0034 | operations | `0034-releases-and-versions.md` | 0029 |  |
| 0049 | `docs/adr/search/0049-home-examples.md` | 0015 | search | `0015-home-examples.md` | — |  |
| 0050 | `docs/adr/frontend/0050-pages-for-crawlers.md` | 0029 | frontend | `0029-site-pages-and-policy.md` | 0052 |  |
| 0051 | `docs/adr/scout/0051-tavily-discovery.md` | 0022 | scout | `0022-discovery.md` | 0058, 0063 |  |
| 0052 | `docs/adr/operations/0052-policy-endpoint.md` | 0029 | frontend | `0029-site-pages-and-policy.md` | 0050 |  |
| 0053 | `docs/adr/search/0053-category-filter.md` | 0013 | search | `0013-filters-facets-and-categories.md` | 0010, 0013 |  |
| 0054 | `docs/adr/quality/0054-dead-code-and-dependency-audit.md` | 0037 | quality | `0037-code-format-and-dead-code-audit.md` | 0047 |  |
| 0055 | `docs/adr/search/0055-search-inside-awesome-lists.md` | 0010 | search | `0010-catalog-full-text-index.md` | 0011 |  |
| 0056 | `docs/adr/scout/0056-evidence-ladder.md` | 0023 | scout | `0023-evidence-gated-enrichment.md` | 0019, 0043, 0062 |  |
| 0057 | `docs/adr/frontend/0057-monetisation.md` | 0030 | frontend | `0030-monetisation.md` | 0059 |  |
| 0058 | `docs/adr/scout/0058-awesome-deep-search.md` | 0022 | scout | `0022-discovery.md` | 0051, 0063 |  |
| 0059 | `docs/adr/frontend/0059-adsense.md` | 0030 | frontend | `0030-monetisation.md` | 0057 |  |
| 0060 | `docs/adr/operations/0060-runtime-settings.md` | 0007 | foundation | `0007-configuration-secrets-and-settings.md` | 0007 |  |
| 0061 | `docs/adr/scout/0061-linkedin-via-tavily.md` | 0021 | scout | `0021-collectors-and-events.md` | 0020, 0022, 0067 |  |
| 0062 | `docs/adr/scout/0062-writing-about-a-resource.md` | 0023 | scout | `0023-evidence-gated-enrichment.md` | 0019, 0043, 0056 |  |
| 0063 | `docs/adr/scout/0063-discovery-hosts.md` | 0022 | scout | `0022-discovery.md` | 0051, 0058 |  |
| 0064 | `docs/adr/edge/0064-relationship-target-in-review.md` | 0026 | edge | `0026-back-office-and-review.md` | 0045, 0065, 0066 |  |
| 0065 | `docs/adr/edge/0065-candidate-edit-in-review.md` | 0026 | edge | `0026-back-office-and-review.md` | 0045, 0064, 0066 |  |
| 0066 | `docs/adr/edge/0066-curated-field.md` | 0026 | edge | `0026-back-office-and-review.md` | 0045, 0064, 0065 |  |
| 0067 | `docs/adr/scout/0067-live-web-events-scout.md` | 0021 | scout | `0021-collectors-and-events.md` | 0020, 0022, 0061 | Confirmed by Silvio, 7 Nov 2026. An events collector: it feeds the same events list as 0022 and amends 0022 §6. |
| 0068 | `docs/adr/foundation/0068-backend-libraries-and-apps.md` | 0002 | foundation | `0002-modular-monolith-libraries-and-apps.md` | 0002 | Confirmed by Silvio, 7 Nov 2026. The module map: libraries and apps are how the modules of 0002 are built. |
| 0069 | `docs/adr/operations/0069-three-deployables.md` | 0032 | operations | `0032-runtime-topology.md` | 0027 | Confirmed by Silvio, 7 Nov 2026. What runs where: three images, Deployments and hosts, as 0027 describes the chart. |
| 0070 | `docs/adr/operations/0070-app-roles.md` | 0004 | foundation | `0004-persistence-schema-and-app-roles.md` | 0004 | Confirmed by Silvio, 7 Nov 2026. The roles and grants live in the schema migration and AppRolesGrantsIT, which 1b rewrites with 0004. |
| 0071 | `docs/adr/operations/0071-ci-cd-per-component-and-image-signing.md` | 0033 | operations | `0033-ci-cd-and-supply-chain.md` | 0028, 0046 | Confirmed by Silvio, 7 Nov 2026. CI and CD per component and image signing extend 0028 and 0046. |
