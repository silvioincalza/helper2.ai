package ai.helper2.examples.usecase;

import ai.helper2.examples.HomeExamples;
import ai.helper2.platform.AuditTrail;
import java.util.UUID;

public class RemoveHomeExample {

  private final HomeExamples examples;
  private final AuditTrail audit;

  public RemoveHomeExample(HomeExamples examples, AuditTrail audit) {
    this.examples = examples;
    this.audit = audit;
  }

  public boolean remove(String adminId, UUID id) {
    boolean removed = examples.remove(id);
    audit.record(
        adminId,
        AuditTrail.Action.REMOVE_HOME_EXAMPLE,
        id.toString(),
        removed ? "REMOVED" : "NOT_FOUND");
    return removed;
  }
}
