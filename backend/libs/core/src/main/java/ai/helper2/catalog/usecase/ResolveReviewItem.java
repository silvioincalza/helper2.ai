package ai.helper2.catalog.usecase;

import ai.helper2.catalog.ReviewDecisions;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.platform.AuditTrail;
import ai.helper2.platform.BadRequestException;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class ResolveReviewItem {

  private final ReviewDecisions decisions;
  private final ReviewQueue queue;
  private final AuditTrail audit;

  public ResolveReviewItem(ReviewDecisions decisions, ReviewQueue queue, AuditTrail audit) {
    this.decisions = decisions;
    this.queue = queue;
    this.audit = audit;
  }

  public sealed interface Outcome {
    record Resolved(ReviewQueue.Item item) implements Outcome {}

    record NotOpen(ReviewQueue.Item item) implements Outcome {}

    record NotFound() implements Outcome {}
  }

  public Outcome resolve(UUID id, ReviewQueue.Status decision, String by, String note) {
    return resolve(id, decision, by, note, null);
  }

  public Outcome resolve(
      UUID id, ReviewQueue.Status decision, String by, String note, @Nullable UUID batch) {
    if (decision != ReviewQueue.Status.ACCEPTED && decision != ReviewQueue.Status.REJECTED) {
      throw new BadRequestException("decision must be ACCEPTED or REJECTED");
    }
    boolean decided = decisions.decide(id, decision, by, note);
    Outcome outcome = outcomeOf(queue.find(id), decided);
    audit.record(
        by,
        AuditTrail.Action.RESOLVE_REVIEW_ITEM,
        id.toString(),
        inBatch(recorded(outcome, decision), batch));
    return outcome;
  }

  public static String inBatch(String outcome, @Nullable UUID batch) {
    return batch == null ? outcome : outcome + " bulk:" + batch;
  }

  private static Outcome outcomeOf(Optional<ReviewQueue.Item> item, boolean decided) {
    return item.<Outcome>map(i -> decided ? new Outcome.Resolved(i) : new Outcome.NotOpen(i))
        .orElseGet(Outcome.NotFound::new);
  }

  private static String recorded(Outcome outcome, ReviewQueue.Status decision) {
    return switch (outcome) {
      case Outcome.Resolved r -> decision.name();
      case Outcome.NotOpen n -> "NOT_OPEN";
      case Outcome.NotFound f -> "NOT_FOUND";
    };
  }
}
