package ai.helper2.catalog.usecase;

import ai.helper2.catalog.KnownTarget;
import ai.helper2.catalog.KnownTargetCandidates;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.platform.AuditTrail;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class EditKnownTargetCandidate {

  public enum Outcome {
    EDITED,
    NOT_FOUND,
    NOT_OPEN,
    NOT_A_PROPOSAL,
    INVALID
  }

  private final ReviewQueue queue;
  private final KnownTargetCandidates candidates;
  private final AuditTrail audit;

  public EditKnownTargetCandidate(
      ReviewQueue queue, KnownTargetCandidates candidates, AuditTrail audit) {
    this.queue = queue;
    this.candidates = candidates;
    this.audit = audit;
  }

  public Outcome edit(
      UUID itemId,
      KnownTarget.Kind kind,
      @Nullable String name,
      @Nullable List<String> otherNames,
      String by) {
    Optional<ReviewQueue.Item> item = queue.find(itemId);
    if (item.isEmpty()) {
      return Outcome.NOT_FOUND;
    }
    if (item.get().kind() != ReviewQueue.Kind.KNOWN_TARGET_CANDIDATE) {
      return Outcome.NOT_A_PROPOSAL;
    }
    Map<String, String> names = ManageKnownTargets.names(name, otherNames);
    if (names == null) {
      return Outcome.INVALID;
    }
    List<String> shown = List.copyOf(names.values());
    if (!candidates.change(itemId, kind, shown)) {
      return Outcome.NOT_OPEN;
    }
    audit.record(
        by,
        AuditTrail.Action.EDIT_CANDIDATE,
        itemId.toString(),
        "EDITED " + kind.name() + " " + shown.getFirst());
    return Outcome.EDITED;
  }
}
