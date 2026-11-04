/**
 * The catalog's API for enrichment: what the Scout reads to pick and prepare a resource ({@link
 * ai.helper2.catalog.enrichment.EnrichmentCandidates}) and what it hands back after a run ({@link
 * ai.helper2.catalog.enrichment.EnrichmentResults}). The catalog owns the tables behind both.
 */
@org.springframework.modulith.NamedInterface("enrichment")
package ai.helper2.catalog.enrichment;
