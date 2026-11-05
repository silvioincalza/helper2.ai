package ai.helper2.examples.usecase;

import ai.helper2.examples.HomeExamples;
import ai.helper2.identity.AuditTrail;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** An administrator removes an example. Removing one that is gone is not an error. */
@Service
public class RemoveHomeExample {

  private final HomeExamples examples;
  private final AuditTrail audit;

  public RemoveHomeExample(HomeExamples examples, AuditTrail audit) {
    this.examples = examples;
    this.audit = audit;
  }

  @Transactional
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
