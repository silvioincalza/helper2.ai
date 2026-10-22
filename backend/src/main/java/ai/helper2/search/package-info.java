/**
 * Federated search: domain (Kotlin, framework-free) at the top level; adapters (cache, web, config)
 * in sub-packages. Outbound ports: SearchSource, SearchCache, ResultObserver, SearchMetrics.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Federated search")
package ai.helper2.search;
