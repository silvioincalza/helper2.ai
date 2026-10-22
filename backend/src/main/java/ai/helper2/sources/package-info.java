/**
 * Source connectors (adapters) for permitted public APIs. Each implements the search module's
 * SearchSource port and/or the scout module's Collector port. Terms, limits and attribution for each
 * are recorded in docs/sources.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Source connectors")
package ai.helper2.sources;
