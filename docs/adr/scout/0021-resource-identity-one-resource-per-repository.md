# 0021 — Resource identity: one resource per repository

**Depends on:** [0004](../foundation/0004-persistence-and-the-schema.md), [0022](../search/0022-federated-search.md)

## Context
The same thing reaches helper2.ai under several addresses. A library is a GitHub repository, an npm package, a PyPI
project and a line in an awesome list. One folder of a repository is linked from `main`, from a tag and from the MCP
Registry. A package is seen in one search with its repository and in the next one alone.

A visitor wants one result for one thing. The catalog wants one row that every source adds to, whatever arrives
first. And an id that is shown, saved in a bookmark or linked from a review item must keep meaning the same thing.

## Decision
1. **The id comes from a canonical web address (URL).** `CanonicalUrl` forces `https`, lower-cases the host, drops
   `www.`, the default port, the fragment, `utm_*` and other tracking parameters and the trailing slash, and sorts
   the query. A `git+`, `git://`, `ssh://` or `git@` address becomes `https`. `ResourceId` is a universally unique
   identifier (UUID) version 5 (RFC 9562) of that URL in a fixed namespace; SQL computes the same id with
   `uuid_generate_v5` ([0004](../foundation/0004-persistence-and-the-schema.md) §2). A path with a dot segment (`.`
   or `..`, also written `%2e`) has no canonical URL: a browser would open another page than the one the id names.
   Three hosts have their own rules:
   - **GitHub.** A repository page becomes `https://github.com/{owner}/{repo}` in lower case, without `.git`. A
     file, the issues, the releases and the other repository pages name the repository: a list that links a file of
     a project means the project. GitHub's own site pages (`marketplace`, `features`, `topics`, `orgs` and the
     others in `CanonicalUrl`) keep their path: they are not repositories.
   - **arXiv.** Every view and version of a paper (`abs`, `pdf`, `html`, `vN`) becomes `https://arxiv.org/abs/{id}`,
     and a Hugging Face paper page (`huggingface.co/papers/{id}`) is the same paper.
   - **Hugging Face.** `hf.co` is `huggingface.co`; a model, dataset or Space page names its repository
     (`{owner}/{name}`, `datasets/{owner}/{name}`, `spaces/{owner}/{name}`). The case of the path is kept.
2. **The git ref is ignored.** A sub-directory of a repository (`/tree/{ref}/{path}`) keeps its path but not its
   branch or tag: it becomes `/tree/HEAD/{path}`. So `tree/main/x`, `tree/v1.2/x` and `tree/HEAD/x` are one resource
   (GitHub opens `HEAD` on the default branch). A README in a sub-folder (`/blob/{ref}/{folder}/README.md`) names
   that folder (`/tree/HEAD/{folder}`); a README at the root names the repository. A sub-directory is a different
   address from the repository root: a package or an MCP server that states its sub-directory is the resource of
   that sub-directory.
3. **A package joins the repository it states when its scope or its name says it belongs there.** A record states
   its repository through an npm package's `links.repository`, a PyPI project's source link or an MCP Registry
   server's `repository.url`. `RepositoryJoin` decides whether the record joins it. The names are compared by
   words (lower case, split on anything that is not a letter or a digit), so `@langchain` is the owner
   `langchain-ai` and `@tanstack` is `TanStack`. A record joins the repository it states when:
   - the stated repository is a sub-directory (`/tree/HEAD/{path}`): one folder is one package or one server. A
     repository in a nested group (`gitlab.com/group/subgroup/name`) is a repository, named by its last part, and
     a repository stated by its host alone is named by the host;
   - its scope is the repository's owner (`@langchain/core` under `langchain-ai/langchainjs`,
     `@microsoft/postgres-mcp-darwin-arm64` under `microsoft/postgres-mcp`); or
   - its name holds the repository's telling words, the words of its name without the generic words of §5
     (`@ai-sdk/openai` under `vercel/ai`, `langchain-openai` under `langchain-ai/langchain`, `firecrawl-mcp` under
     `firecrawl/firecrawl-mcp-server`).

   Any other record does not join: a package whose scope is not the repository's owner and whose name does not
   hold the repository's telling words (`alpha-go` stating `lazy-punk/ml-utils`) is a resource of its own. A joined record
   takes the repository's address as its resource's identity; every other record takes its own. `ResultMerger`
   joins each hit's canonical URL with the repository `RepositoryJoin` gives it (union-find) and names the group by
   that repository, else by the canonical URL of one of its own hits, a GitHub URL first. The join and the identity
   use the same rule, so a hit alone never takes a repository it does not join. The id does not depend on which
   hits arrive together: a search that sees the package alone writes its record to the repository's resource,
   whether or not the repository's own hit is in the result set. The record keeps the repository it states
   (`source_record.repository_url`), joined or not: the Scout reads the README and the facts from it, and the
   resource page shows it as the record's Repository link.
4. **All packages that join one repository merge into it.** Every package that joins the same repository is a
   record of that repository's resource, so a monorepo with many packages is one result. The result shows one link
   per source.
5. **An MCP Registry server is its own resource, keyed on its registry name.** A server that states a
   sub-directory is the resource of that sub-directory (§2). Any other server is the resource of its registry
   name, `https://registry.modelcontextprotocol.io/v0.1/servers/{name}` (`McpRegistryCollector.identity`), whatever
   host the collector reads, and it states its repository. It joins the repository root only when every telling
   word of one name is in the other: the server's name (the part after the namespace, `a1lane` in
   `ai.a1lane/a1lane`) and the repository's name, without `mcp`, `server`, `servers`, `model`, `context`, `protocol`
   and `modelcontextprotocol`. So `epa-emissions` joins `pipeworx-io/mcp-epa-emissions`, `leash` joins
   `open-leash/leash-plugin` and `uk-livestock-mcp` joins `ansvar-systems/uk-livestock`; a name of these words only
   (`modelcontextprotocol/servers`) is read as it is. A repository with one server is still one result, and a
   collection repository with many servers (`codespar/mcp-dev-latam`, 127 servers: `mcp-adyen`, `mcp-afip`, …) is one
   result per server, because its servers have words of their own. The registry namespace is the owner of every
   server it publishes, so the owner alone does not join a server.
6. **A resource is never moved; a source record follows its resource.** What a later source says is added to the
   resource that exists. The catalog writes a resource with `INSERT ... ON CONFLICT (canonical_url) DO UPDATE`
   (`JdbcCatalogWriter.upsertResources`): the id and the address of a stored resource never change. A source record
   is one row per source and external id, and it belongs to the resource its source names. When the source names
   another one (a package stored alone that later states its repository), the record moves there
   (`JdbcCatalogWriter.upsertRecords`), and the user reports and review items of a resource left without records
   move with it ([0004](../foundation/0004-persistence-and-the-schema.md) §3).
7. **New facts are merged into the existing resource.** A write never overwrites the fields of a resource from a
   stronger origin, and the reconciled facts are rebuilt from all its records
   ([0004](../foundation/0004-persistence-and-the-schema.md) §3). A stored resource keeps the title, link,
   description and language it shows when a batch brings only records that state it (a package without its
   repository); a record at the resource's own address sets them.
8. **An address a record states still finds its resource.** `JdbcResources.ownerOfRecordUrl` resolves the id of a
   record's own address (the npm or PyPI page) to the resource that holds the record, so a link or a bookmark made
   from the package page opens the repository's resource.

### Code
- `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/CanonicalUrl.kt`, `ResourceId.kt` — the canonical URL and
  the id.
- `backend/libs/core/src/main/kotlin/ai/helper2/search/RepositoryJoin.kt` (`of`) — whether a record joins the
  repository it states.
- `backend/libs/core/src/main/kotlin/ai/helper2/search/ResultMerger.kt` (`joiningRepositories`, `group`,
  `identity`) — the package joins its repository.
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/mcp/McpRegistryCollector.java` (`identity`) — the
  registry name or the stated folder of an MCP server.
- `backend/libs/core/src/main/java/ai/helper2/catalog/ResourceDetail.java` (`SourceRecordInfo.of`),
  `frontend/src/features/resource/WhereFrom.tsx` — the repository a record states, on the resource page.
- `backend/libs/core/src/main/java/ai/helper2/catalog/internal/JdbcCatalogWriter.java` (`upsertResources`,
  `upsertRecords`), `JdbcResources.java` (`ownerOfRecordUrl`), `UrlKeys.java` — the write, and the id in SQL.
- `backend/libs/schema/src/main/resources/db/migration/V1__schema.sql` — `resource.canonical_url`,
  `source_record_identity_idx`.
- `backend/libs/core/src/test/kotlin/ai/helper2/search/ResultMergerTest.kt`, `RepositoryJoinTest.kt`,
  `backend/apps/backoffice/src/test/java/ai/helper2/sources/mcp/McpRegistryCollectorTest.java`,
  `backend/apps/public/src/test/java/ai/helper2/catalog/internal/JdbcCatalogWriterIT.java` — a package alone, a
  package with its repository, several packages of one repository, a sub-directory, a package of another owner,
  the servers of a collection repository, a repository with one server.

## Alternatives considered
- **A package as a resource of its own, linked to its repository** — two results for one thing, and the stars of
  the repository and the downloads of the package rank apart.
- **Join every package with any repository it states** — a fork or a copy that states the original's repository
  (under `huggingface/transformers`, `vllm-project/vllm`, `livekit/agents`) becomes the original, and a collection
  repository swallows every server it holds (`codespar/mcp-dev-latam` holds 127).
- **Join a registry server by its owner** — the registry namespace is the owner of every server it publishes, so a
  collection repository would still be one result.
- **Other name rules for a registry server**, measured on the staging catalog (16 Nov 2026: 26,017 repositories with
  one server, 5,066 servers in 160 repositories with five or more), as "one-server repository stays one result /
  collection server is its own result": the full name (`io.github.owner/name` = `owner/name`) 52.7% / 99.7%; the
  name holds the repository's name 61.0% / 97.5%; the name holds every telling word of the repository's 77.0% /
  96.3%; one telling word in common 85.6% / 92.9%; every telling word, either way (this rule) 88.0% / 96.1%. A
  server name often drops or adds a word of its repository's (`epa-emissions` under `mcp-epa-emissions`, `leash`
  under `leash-plugin`), so one-direction rules split one-server repositories, and one shared word joins too many
  collection servers.
- **Join a registry server unless another server states the same repository** — it splits collections exactly, but
  the id of a server would depend on what the catalog holds when it is written.
- **The stated repository as a relationship (`relationship` table)** — that table holds enrichment claims with
  their evidence; a repository the source states is a fact of the record, so it stays on the record.
- **Join only when both hits are in one result set** — the id would depend on what a search happened to return, and
  the writer would need a rule of its own to keep the join.
- **Keep the branch or tag in the address of a sub-directory** — the same folder linked from `main`, from a tag and
  by the MCP Registry would be three resources.
- **Random ids with an alias table** — an id would say nothing about the address, and every writer would have to
  look the alias up first.
- **Move a resource to a new id when a better address is found** — bookmarks, reports, review items and links would
  point at an id that is gone.

## Consequences
- A package and its repository are one result and one catalog row, whichever arrives first.
- The 40 LangChain packages are the LangChain repository: one result, not 40.
- A collection repository of MCP servers is one result per server, each with a link to the repository; a
  repository with one server is one result.
- A package that only states someone else's repository is a result of its own, not the original.
- No data migration: a record follows its resource (§6), so the next write of a server or a package moves its
  record to the resource the rule names.
- Download counts of several packages are kept per record and one is shown.
- The id of a repository can be computed from its address alone, in Kotlin and in SQL.

### Known limits
- The result shows one link per source: with several packages of one source, one of them (the first by external
  id).
- Until the repository's own record arrives, the resource carries the first package's title and description.
- A package found without a stated repository is a result of its own until a source states the repository.
- A resource left without records is deleted only when it is `OBSERVED` and no report, review item or curated
  field holds it; an `INDEXED` or `CURATED` one stays as a row without records. Its relationships and labels do not
  move with the records.
- A single file in a repository (one prompt of a prompt library) is not a resource of its own: a link to it names
  the repository.
- A sub-folder of a repository is one resource under any branch or tag: a folder whose content differs between two
  branches is still one resource, and a branch name with a slash is read as its first part.
- The npm search API returns the repository URL only, not `repository.directory`: the sub-directory of a package is
  known only when the package states it in the URL.
- A fork or a copy whose name holds the repository's name still joins it (`vllm-npu` under `vllm-project/vllm`,
  `divyanx-transformers` under `huggingface/transformers`), and a short repository name is held by many package
  names (`ai-toolkit` joins `vercel/ai`).
- An official package without a scope whose name does not hold the repository's name is a result of its own: `openai`
  under `openai/openai-python`, `torch` under `pytorch/pytorch`, `mcp` under `modelcontextprotocol/python-sdk`. The
  collectors do not read who publishes a package (no personal data, [0017](0017-pypi-through-bigquery.md)), so the
  owner of an unscoped package is unknown.
- A record that does not join its repository does not reach the admin review list, and an admin cannot join it by
  hand (S2.10.6).
- A registry server whose telling words are all in a collection repository's name, or the other way round, joins
  it (`airtable` under `acme/airtable-tools`); about 4% of the servers of repositories with five or more (staging,
  16 Nov 2026).
- A repository with one server whose name and the server's do not hold each other's telling words shows the
  repository and the server as two results (`com.wavebrook/dashboard` under `wavebrook/ai-plugins`); about 12% of
  those repositories. S2.10.6 lets an admin join them.

## Diagrams
[flow](../../diagrams/flow-0021-resource-identity.svg) · [sequence](../../diagrams/sequence-0021-resource-identity.svg)

## Backlog
- Decides: [F2.10](https://github.com/silvioincalza/helper2.ai/issues/40), S2.10.5 (each MCP server of a
  collection repository, and a stranger's package, as a result of its own).
- Open issues that change it:
  - [S2.10.3](https://github.com/silvioincalza/helper2.ai/issues/360): a source record stays with its resource.
  - S2.10.6: a refused package join on the admin review list, and accepting it joins.
  - S2.10.7: an official package without a scope joins its repository by its publisher.
