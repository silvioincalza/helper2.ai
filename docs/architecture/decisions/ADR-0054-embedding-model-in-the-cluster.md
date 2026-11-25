# 0054 — The embedding model runs in the cluster

**Status:** Accepted

**Date:** 2026-11-20

**Depends on:** [0006](ADR-0006-outbound-calls-and-resilience.md), [0024](ADR-0024-vector-search.md), [0043](ADR-0043-runtime-topology-helm-chart-three-deployables.md), [0044](ADR-0044-ci-cd-and-supply-chain.md).

**Requirements:** NFR-003, C-002

## Context

Vector search needs one embedding per catalog resource and one per new search query ([0024](ADR-0024-vector-search.md)).
The model is `nvidia/nemotron-3.5-embed-1b`. NVIDIA's hosted endpoint for it (`integrate.api.nvidia.com`) is a free,
rate-limited endpoint with no availability promise, and it answers 503 "Service is overloaded" for long stretches:
on 20 Nov 2026 every indexer call in production failed for more than an hour. The same endpoint also receives
every search query as typed.

The weights are public (`nvidia/Nemotron-3.5-Embed-1B-BF16` on Hugging Face, OpenMDW-1.1, "ready for commercial or
non-commercial use", read 20 Nov 2026). The load is small: one text every few seconds for the indexer, and one short
query per search that is not in the 7-day query vector cache.

## Decision

1. **One pod for production and staging.** `searchavo-embedder` is one more deployable of the Helm chart
   (`templates/embedder.yaml`). The production release runs it (`embedder.enabled`), and staging runs none: its
   apps call production's (`embedder.url`, `embedder.namespace`). No release calls NVIDIA's hosted endpoint.
   The pod holds the model in memory and answers `POST /v1/embeddings` with the body NVIDIA's endpoint takes:
   `model`, `input`, `input_type` (`query` or `passage`), `dimensions`. The application's client
   (`NvidiaEmbedder`) does not change.
2. **Inside the cluster only.** A ClusterIP Service on port 8080, no Ingress. The network policy
   `ingress-embedder` lets in the public app and the back office of its own namespace and of the namespaces in
   `embedder.clientNamespaces` (staging, and a canary there), nothing else. The embedder has no way out: the default egress policy
   denies everything and no policy opens one for it. It needs none, since the weights are in the image. Staging's
   apps are let out to the embedder pods of production's namespace and to nothing else there. `embedder.url` must be
   the embedder Service of `embedder.namespace`: any other address fails the render. `make chart-check` fails a
   render that exposes the embedder or lets another caller in.
3. **The chart points the apps at it.** A release that runs or calls the embedder sets, on the public app and the
   back office, `SA_NVIDIA_EMBEDDING_BASE_URL`, `SA_NVIDIA_EMBEDDING_MODEL`, `SA_NVIDIA_API_KEY=in-cluster` and
   `SA_EMBEDDING_EVERY` (`embedder.indexEvery`: 3 s in production, 1 minute in staging, which shares production's pod),
   which override the values of the env Secret. The key only
   switches vector search on ([0024](ADR-0024-vector-search.md) §3): the embedder reads no credential, the
   network policy is what keeps others out. Production's indexer embeds one text every 3 s and staging's one a
   minute: no outside rate limit holds them back, but the embedder takes one call at a time and search queries wait
   in the same line.
4. **The server** (`deploy/embedder/`): Python, Sentence Transformers on PyTorch for CPU, the standard library's HTTP
   server. `embedding_api.py` reads and checks the call and shapes the answer, with no model in it, so its tests run
   without the weights; `server.py` loads the model and serves.
   - The prefix (`query: ` or `passage: `) comes from the checkpoint and follows `input_type`. A call without a
     valid `input_type` is refused: the same text embedded with and without its prefix has a cosine of 0.30
     (measured, 20 Nov 2026), so a missing prefix would break retrieval with no error.
   - The model's vector has 2048 dimensions. A shorter one is its first `dimensions` values, normalised again
     (128 to 2048), as the model card describes.
   - A text is cut at `embedder.maxTokens` (1024) from the end.
   - One call runs at a time; the others wait. A call holds at most 32 texts and 4 MB.
   - 400 for a call it cannot read, 413 for a body over the limit, 500 for a failure of the model. `GET /healthz`
     answers once the model is loaded and has embedded one text.
5. **The weights are in the image.** `deploy/embedder/Dockerfile` downloads one pinned commit of the model
   repository at build time, and installs the packages of `requirements.txt` by hash. The pod starts with no
   network call and runs with a read-only root filesystem as user 10001.
6. **Built, signed and scanned like the Postgres image** ([0044](ADR-0044-ci-cd-and-supply-chain.md)). The tag is `embedder.image.tag`
   in `values.yaml`. CD builds and pushes it only when Harbor does not have the tag (`make embedder-image`), signs
   what it pushed, writes its SBOM (`make embedder-sbom`) and scans it. A deploy checks its signature and passes its
   digest to Helm (`EMB_DIGEST`, `embedder.image.digest`). A pull request that changes `deploy/embedder` without a
   new tag fails (`ci.yml`, job `embedder`), which also runs the API tests.
7. **Size.** In production `embedder.threads` 4, requests 4 CPU and 6 Gi, limit 8 Gi (the chart's default is 2
   threads and 1 CPU), one replica, up to 300 s to start
   (`embedder.startupSeconds`). `embedder.nodeSelector` places it when a node must be avoided.
8. **One rollout.** The embedder is rolled out with the production release. Staging gets a new embedder when
   production does.

### Code

- `deploy/embedder/embedding_api.py`, `server.py`, `test_embedding_api.py` — the server and its tests.
- `deploy/embedder/Dockerfile`, `requirements.in`, `requirements.txt` — the image; `requirements.txt` is written by
  `uv pip compile` with hashes for Linux x86-64 and the CPU build of PyTorch.
- `deploy/helm/searchavo/templates/embedder.yaml`, `_helpers.tpl` (`searchavo.embedderImage`, `searchavo.embedderEnv`),
  `networkpolicy.yaml` and `canary.yaml` (the apps' way to it) — the chart.
- `deploy/k8s/Makefile` — `embedder-image`, `embedder-sbom`, `sign`, `verify-images`, `chart-check`.
- `.github/workflows/cd.yml`, `ci.yml`, `deploy.yml`, `release.yml`, `.github/scripts/changes.sh`, `ci-ok.sh`.

## Alternatives considered

- **Stay on NVIDIA's hosted endpoint.** Nothing to run, but it has no availability promise and fails for long
  stretches.
- **A paid serverless provider.** No provider checked offers this model: its page on build.nvidia.com has no
  deployment partner (read 20 Nov 2026). Nebius Token Factory serves `Qwen/Qwen3-Embedding-8B`, which is not an
  NVIDIA model, so it breaks the NVIDIA-only rule ([0008](ADR-0008-llm-integration.md)), re-embeds the
  catalog and needs new distance thresholds.
- **A GPU node with vLLM.** The serving path NVIDIA documents, and faster, but a node type and a monthly cost for
  a load that one CPU pod carries.
- **vLLM on CPU.** Its `/v2/embed` applies the prefix, but the model card pins it to one vLLM version and names
  GPUs only, and it is a much larger server than two routes need.
- **One embedder per environment.** Staging would try a new embedder before production, but each pod holds
  6 Gi for a model that is idle most of the time.
- **The shared embedder in its own namespace, as its own release.** Production and staging would depend on it
  alike, and it would roll out apart from both. It needs a namespace, deployer rights and a deploy step of its own.
- **Weights on a volume, downloaded when the pod starts.** A smaller image, but a start that depends on Hugging
  Face, an egress rule for it, and weights that no signature covers.
- **A web framework (FastAPI, uvicorn) for the server.** More packages to pin and scan for two routes and one call
  at a time.

## Consequences

- No embedding call leaves the cluster: search queries and catalog text stay in it, and NVIDIA's hosted endpoint
  is no longer in the path. The Privacy page says so.
- Vector search no longer depends on an outside provider's capacity or rate limit. It depends on one pod: while it
  restarts, searches lose the results by meaning and keep the keyword ones ([0024](ADR-0024-vector-search.md)),
  and the indexer waits.
- Staging depends on production's namespace for its embeddings, and shares the pod with it: a staging indexer
  that embeds a full catalog waits in the same line as production's search queries.
- A new embedder image reaches production without a run on staging first.
- The model id stays `nvidia/nemotron-3.5-embed-1b`, so the stored vectors keep their model and nothing is embedded
  again.
- The image holds the weights and PyTorch, several gigabytes: the first build and the first pull on a node are
  slow. CD's image job has 60 minutes, and it writes the SBOM of this image with its temporary files in the
  runner's work folder: the layers do not fit in `/tmp`.
- A new model version is a new pinned commit and a new `embedder.image.tag`.

### Known limits

- Latency on the cluster, measured with 2 threads and 1 CPU on a staging pod (one text per call, 20 Nov 2026, #892):
  a query 0.57 s at p50 and 0.64 s at p95; about 400 tokens 3.2 s; about 1,000 tokens 7.5 s at p50 and 7.8 s at
  most. Peak memory about 6.6 GB. The query path allows 3 s and the indexer 30 s
  ([0024](ADR-0024-vector-search.md)). The 4 threads and 4 CPU of production are not measured yet.
- A text is cut at 1,024 tokens, where NVIDIA's endpoint cuts at 10,240. A longer description is embedded by its
  beginning.
- The vectors stored from NVIDIA's endpoint and the ones this server makes are the same space (190 texts, #892):
  median cosine 1.0000, lowest 0.966 on texts of about 4,000 characters, cut at 1,024 tokens here. No new model id.
- About 57,000 texts waited to be embedded when production switched (29,600 in production, 27,700 in staging). A
  search query waits in the same line as both indexers.
- One replica and one call at a time: a burst of new queries waits in line behind the indexers of both
  environments, each query within its 3 s.

## Diagrams

[flow](../diagrams/flow-0054-embedding-model-in-the-cluster.svg) · [sequence](../diagrams/sequence-0054-embedding-model-in-the-cluster.svg) · [infrastructure](../diagrams/infrastructure.svg) · [workloads](../diagrams/infrastructure-workloads.svg)

## Backlog

- Features: [F3.3](https://github.com/silvioincalza/searchavo.ai/issues/49) Vector search: pgvector + NVIDIA embeddings.
- Open issues that change this decision:
  - [#892](https://github.com/silvioincalza/searchavo.ai/issues/892) S3.3.6 Production embeds with the model in the cluster
