/**
 * AI Resource Scout: scheduled deterministic collection (Collector port), change detection via the
 * catalog, and evidence-grounded LLM enrichment (EnrichmentModel port) gated by EvidenceValidator.
 * Hands checked results to the catalog's enrichment interface; the catalog owns and writes the
 * enrichment tables and reads them for detail pages.
 */
@org.springframework.modulith.ApplicationModule(displayName = "AI Resource Scout")
package ai.helper2.scout;
