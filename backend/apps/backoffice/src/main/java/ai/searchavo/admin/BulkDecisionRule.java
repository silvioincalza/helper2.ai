package ai.searchavo.admin;

import ai.searchavo.catalog.ReviewQueue;
import java.util.Collection;
import java.util.Set;

public final class BulkDecisionRule {
  public static final Set<String> DECISIVE_FIELDS = Set.of("license", "openSource", "pricing");

  public enum Research {
    KEEP_WITHOUT_CONFLICT,
    NOT_KEEP
  }

  private BulkDecisionRule() {}

  public static boolean allows(
      ReviewQueue.Status decision,
      ReviewQueue.Kind kind,
      Collection<String> conflictingFields,
      Research research) {
    if (decision == ReviewQueue.Status.REJECTED) {
      return true;
    }
    if (decision != ReviewQueue.Status.ACCEPTED) {
      return false;
    }
    return switch (kind) {
      case ENRICHMENT_VALIDATION -> conflictingFields.stream().noneMatch(DECISIVE_FIELDS::contains);
      case RESOURCE_CANDIDATE, COURSE_CANDIDATE -> research == Research.KEEP_WITHOUT_CONFLICT;
      case RELATIONSHIP_CANDIDATE, USER_REPORT, EXAMPLE_CANDIDATE, KNOWN_TARGET_CANDIDATE -> false;
    };
  }
}
