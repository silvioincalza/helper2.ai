# 0013 — "Works with" flags derived from current source tags

**Status.** Accepted (2026-10-27). **Builds on:** [0004](0004-persistence-and-provenance.md), [0008](0008-federated-search.md)

## Context
Users want to know if a resource works with the tool they already use (OpenAI, Ollama, a Model Context Protocol (MCP) client, VS Code…).
Sources rarely state this as a field, but they often tag it ("ollama", "langchain", "mcp-server").
A model could guess compatibility, but a guess shown as a fact is wrong for this product. A tag that a source removes
must also remove the flag.

## Decision
1. **Rules, no model** (`Compatibility.of(MergedResult)`). A fixed list of 18 tools (`Compatibility.TOOLS`):
   model providers (OpenAI, Anthropic, Google, xAI, Mistral, DeepSeek, NVIDIA), local runtimes (Ollama, llama.cpp,
   Apple MLX, Transformers, vLLM), frameworks (LangChain, LlamaIndex), clients and editors (any MCP client, Claude Code,
   Cursor, VS Code). Each tool has a set of tags and, for some, file formats.
2. **Three bases.** `TYPE`: an `MCP_SERVER` works with any MCP client. `TAG`: a source tag is in the tool's tag set.
   `FORMAT`: the tag `gguf` gives Ollama and llama.cpp. Tags are compared in a normalized form (trimmed, lower case,
   `_` and spaces become `-`).
3. **Evidence on every flag.** `CompatibilityFlag` holds the tool, label, basis, the tag or type seen, and the id of
   the source that stated it (null for `TYPE`). The first source in merge order wins. Flags follow the order of `TOOLS`.
4. **Current tags only.** For catalog hits, tags come from `resource_label`. `ResourceStore.replaceLabels` rebuilds
   these rows from the current `source_record.extra` on every upsert, so a dropped tag drops the flag. At most 20 tags
   per resource are kept (alphabetical), each cut to 60 characters.
5. **On every card.** `CardMapper.card` (named interface `cards` of the search module) adds `compatibleWith`, so
   search results, resource pages, the "new" list, upcoming events and model families show the same flags.
   Guide recommendations (`GuideRecommendation`) do not carry flags. The contract states they are never
   model-generated.

## Consequences
- Good: every flag can be traced to a tag, a type or a format; the rules are public.
- Good: no model cost, no latency, the same input always gives the same flags.
- Bad: a resource whose sources do not tag it gets no flag. No flag does not mean "does not work".
- Bad: the tag lists are kept by hand.
- Bad: for hits from the Helper2 index the flag names `helper2-index`, which relays the tag, not the original source.
- Trade-off: the 20-tag limit can drop a tag that would have given a flag.

## Alternatives considered
- Ask a model per resource — guesses shown as facts, plus cost.
- Store flags at index time — they would go stale when tags change; computing them per card is cheap.
- Read README files for tool names — a mention is not a statement of support.

## Code
- `backend/src/main/kotlin/ai/helper2/search/Compatibility.kt` — tools, rules, flags.
- `backend/src/main/java/ai/helper2/search/web/CardMapper.java` — adds `compatibleWith` to each card.
- `backend/src/main/java/ai/helper2/catalog/internal/ResourceStore.java` — `replaceLabels`, `MAX_TAGS`.
- `api/openapi.yaml` — `CompatibilityFlag`, `ResultCard.compatibleWith`.
- `frontend/src/components/ResultCardView.tsx` — chips, reason as tooltip and screen-reader text.

## Diagrams
[flow](../diagrams/flow-0013-works-with-flags.svg) · [sequence](../diagrams/sequence-0013-works-with-flags.svg)

## Roadmap
Nothing open.
