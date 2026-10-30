/**
 * Source connectors (adapters) for permitted public APIs. Each implements the search module's
 * SearchSource port and/or the scout module's Collector port. One sub-package per provider (github,
 * huggingface, npm, arxiv, tavily, mcp, aideadlines); the awesome-list classifier in classifier; shared
 * HTTP mapping, settings and run intervals in support; bean wiring in config. No other module uses a
 * type of this module: the search and scout modules find the adapters through their ports. Terms, limits
 * and attribution for each are recorded in docs/sources.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Source connectors")
package ai.helper2.sources;
