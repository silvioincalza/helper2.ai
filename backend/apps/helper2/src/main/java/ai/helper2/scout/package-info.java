/**
 * AI Resource Scout: scheduled deterministic collection (Collector port), change detection via the
 * catalog, and evidence-grounded LLM enrichment (EnrichmentModel port) gated by EvidenceValidator.
 * Hands checked results to the catalog's enrichment interface; the catalog owns and writes the
 * enrichment tables and reads them for detail pages. The Scout keeps an admin's decisions on the
 * enrichment queue (requeue, skip, move to the top) in its own table; the catalog's backlog query
 * reads them (ADR 0045 §7).
 */
@org.springframework.modulith.ApplicationModule(displayName = "AI Resource Scout")
package ai.helper2.scout;
