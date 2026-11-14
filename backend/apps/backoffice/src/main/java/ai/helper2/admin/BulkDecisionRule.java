package ai.helper2.admin;

import ai.helper2.catalog.ReviewQueue;
import java.util.Collection;
import java.util.Set;

public final class BulkDecisionRule {
  public static final Set<String> DECISIVE_FIELDS = Set.of("license", "openSource", "pricing");

  private BulkDecisionRule() {}

  public static boolean allows(
      ReviewQueue.Status decision, ReviewQueue.Kind kind, Collection<String> conflictingFields) {
    if (decision == ReviewQueue.Status.REJECTED) {
      return true;
    }
    return decision == ReviewQueue.Status.ACCEPTED
        && kind == ReviewQueue.Kind.ENRICHMENT_VALIDATION
        && conflictingFields.stream().noneMatch(DECISIVE_FIELDS::contains);
  }
}
