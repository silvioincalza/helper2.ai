package ai.helper2.admin;

import ai.helper2.catalog.ReviewQueue;
import java.util.Collection;
import java.util.Set;

/**
 * Which review items one decision may settle in bulk (ADR 0045). Rejecting is safe for every kind:
 * nothing is added or shown. Accepting is safe only for an enrichment check whose run has no
 * conflict on a field people decide by (licence, open source, pricing): every other kind adds to
 * the catalog, verifies a relationship, publishes an example or closes a user's report, so a person
 * accepts it one by one. A new kind is refused until it is added here.
 */
public final class BulkDecisionRule {

  /** Fields whose conflict a person must read before accepting (the enrichment field names). */
  public static final Set<String> DECISIVE_FIELDS = Set.of("license", "openSource", "pricing");

  private BulkDecisionRule() {}

  /**
   * True when [decision] on an item of [kind] may be taken in bulk. [conflictingFields] are the
   * fields its run left CONFLICTING (empty for an item with no run).
   */
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
