package ai.helper2.catalog.usecase;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.internal.ReviewDecisions;
import ai.helper2.identity.AuditTrail;
import ai.helper2.platform.BadRequestException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** An administrator decides an open review item; the decision is recorded in the audit trail. */
@Service
public class ResolveReviewItem {

  private final ReviewDecisions decisions;
  private final ReviewQueue queue;
  private final AuditTrail audit;

  public ResolveReviewItem(ReviewDecisions decisions, ReviewQueue queue, AuditTrail audit) {
    this.decisions = decisions;
    this.queue = queue;
    this.audit = audit;
  }

  /**
   * What a decision did: the item now decided, an item that was already decided, or no item at all.
   */
  public sealed interface Outcome {
    record Resolved(ReviewQueue.Item item) implements Outcome {}

    record NotOpen(ReviewQueue.Item item) implements Outcome {}

    record NotFound() implements Outcome {}
  }

  /**
   * Resolving a relationship candidate is the ONLY way an AI-proposed relationship becomes
   * VERIFIED; rejecting it marks it REJECTED. Other kinds just record the decision. [by] is the
   * administrator's provider subject; the audit entry's outcome is the decision, NOT_OPEN for an
   * item already decided, NOT_FOUND for none. An item already decided is an outcome, not an
   * exception, so the audit entry is committed in every case.
   */
  @Transactional
  public Outcome resolve(UUID id, ReviewQueue.Status decision, String by, String note) {
    if (decision != ReviewQueue.Status.ACCEPTED && decision != ReviewQueue.Status.REJECTED) {
      throw new BadRequestException("decision must be ACCEPTED or REJECTED");
    }
    boolean decided = decisions.decide(id, decision, by, note);
    Optional<ReviewQueue.Item> item = queue.find(id);
    Outcome outcome =
        item.<Outcome>map(i -> decided ? new Outcome.Resolved(i) : new Outcome.NotOpen(i))
            .orElseGet(Outcome.NotFound::new);
    String recorded =
        switch (outcome) {
          case Outcome.Resolved r -> decision.name();
          case Outcome.NotOpen n -> "NOT_OPEN";
          case Outcome.NotFound f -> "NOT_FOUND";
        };
    audit.record(by, AuditTrail.Action.RESOLVE_REVIEW_ITEM, id.toString(), recorded);
    return outcome;
  }
}
