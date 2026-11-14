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
3. **A package and its repository are one resource.** A record that states its repository (an npm package's
   `links.repository`, a PyPI project's source link) takes the repository's address as its resource's identity;
   every other record takes its own. `ResultMerger` joins each hit's canonical URL with the repository it states
   (union-find) and names the group by the repository its hits state, else by the canonical URL of one of its own
   hits, a GitHub URL first. The id does not depend on which hits arrive together: a search that sees the package
   alone writes its record to the repository's resource, whether or not the repository's own hit is in the result
   set.
4. **All packages of one repository merge into it.** Every package that states the same repository is a record of
   that repository's resource, so a monorepo with many packages is one result. The result shows one link per
   source.
5. **A resource is never moved; a source record follows its resource.** What a later source says is added to the
   resource that exists. The catalog writes a resource with `INSERT ... ON CONFLICT (canonical_url) DO UPDATE`
   (`JdbcCatalogWriter.upsertResources`): the id and the address of a stored resource never change. A source record
   is one row per source and external id, and it belongs to the resource its source names. When the source names
   another one (a package stored alone that later states its repository), the record moves there
   (`JdbcCatalogWriter.upsertRecords`), and the user reports and review items of a resource left without records
   move with it ([0004](../foundation/0004-persistence-and-the-schema.md) §3).
6. **New facts are merged into the existing resource.** A write never overwrites the fields of a resource from a
   stronger origin, and the reconciled facts are rebuilt from all its records
   ([0004](../foundation/0004-persistence-and-the-schema.md) §3). A stored resource keeps the title, link,
   description and language it shows when a batch brings only records that state it (a package without its
   repository); a record at the resource's own address sets them.
7. **An address a record states still finds its resource.** `JdbcResources.ownerOfRecordUrl` resolves the id of a
   record's own address (the npm or PyPI page) to the resource that holds the record, so a link or a bookmark made
   from the package page opens the repository's resource.

### Code
- `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/CanonicalUrl.kt`, `ResourceId.kt` — the canonical URL and
  the id.
- `backend/libs/search/src/main/kotlin/ai/helper2/search/ResultMerger.kt` (`joiningRepositories`, `group`,
  `identity`) — the package joins its repository.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/JdbcCatalogWriter.java` (`upsertResources`,
  `upsertRecords`), `JdbcResources.java` (`ownerOfRecordUrl`), `UrlKeys.java` — the write, and the id in SQL.
- `backend/libs/schema/src/main/resources/db/migration/V1__schema.sql` — `resource.canonical_url`,
  `source_record_identity_idx`.
- `backend/libs/search/src/test/kotlin/ai/helper2/search/ResultMergerTest.kt`,
  `backend/apps/public/src/test/java/ai/helper2/catalog/internal/JdbcCatalogWriterIT.java` — a package alone, a
  package with its repository, several packages of one repository, a sub-directory.

## Alternatives considered
- **A package as a resource of its own, linked to its repository** — two results for one thing, and the stars of
  the repository and the downloads of the package rank apart.
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

## Diagrams
[flow](../../diagrams/flow-0021-resource-identity.svg) · [sequence](../../diagrams/sequence-0021-resource-identity.svg)

## Backlog
- Decides: [F2.10](https://github.com/silvioincalza/helper2.ai/issues/40).
- Open issues that change it:
  - [S2.10.3](https://github.com/silvioincalza/helper2.ai/issues/360): a source record stays with its resource.
