/**
 * AI Resource Scout: scheduled deterministic collection (Collector port), change detection via the
 * catalog, and evidence-grounded LLM enrichment (EnrichmentModel port) gated by EvidenceValidator.
 * Writes the enrichment tables (enrichment_run, evidence, field_assertion, relationship,
 * summary_claim); the catalog reads them for detail pages.
 */
@org.springframework.modulith.ApplicationModule(displayName = "AI Resource Scout")
package ai.helper2.scout;
