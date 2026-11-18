package ai.helper2.catalog.usecase;

import ai.helper2.catalog.ResourceRemoval;
import ai.helper2.platform.AuditTrail;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class RemoveResource {

  public static final int MAX_REASON = 500;

  private static final int MAX_AUDIT_VALUE = 100;

  public sealed interface Outcome {
    record Removed() implements Outcome {}

    record NoReason(int maxLength) implements Outcome {}

    record NoSuchResource() implements Outcome {}
  }

  private final ResourceRemoval removal;
  private final AuditTrail audit;

  public RemoveResource(ResourceRemoval removal, AuditTrail audit) {
    this.removal = removal;
    this.audit = audit;
  }

  public Outcome remove(UUID resourceId, @Nullable String reason, String by) {
    String why = reason == null ? "" : reason.strip();
    if (why.isEmpty() || why.length() > MAX_REASON) {
      return new Outcome.NoReason(MAX_REASON);
    }
    return removal
        .remove(resourceId)
        .<Outcome>map(removed -> {
          audit.record(
              by,
              AuditTrail.Action.REMOVE_RESOURCE,
              resourceId.toString(),
              "REMOVED",
              new AuditTrail.Change(auditValue(removed.canonicalUrl()), null, why));
          return new Outcome.Removed();
        })
        .orElseGet(Outcome.NoSuchResource::new);
  }

  private static String auditValue(String url) {
    return url.length() <= MAX_AUDIT_VALUE ? url : url.substring(0, MAX_AUDIT_VALUE - 1) + "…";
  }
}
