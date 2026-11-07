package ai.helper2.catalog;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port of the catalog for the detail page: what enrichment runs left on a resource, each
 * fact with its evidence. The write side is {@link
 * ai.helper2.catalog.enrichment.EnrichmentResults}.
 */
public interface EnrichmentFacts {

  /** The resource's AI-extracted fields, each with its evidence. */
  List<ResourceDetail.Assertion> assertions(UUID resourceId);

  /** The resource's relationships no reviewer rejected, each with its evidence. */
  List<ResourceDetail.Relationship> relationships(UUID resourceId);

  /** The summary of the latest run that has one, or null. */
  ResourceDetail.@Nullable Summary summary(UUID resourceId);
}
