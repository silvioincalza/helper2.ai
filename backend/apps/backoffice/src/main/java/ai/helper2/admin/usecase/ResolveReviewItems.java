package ai.helper2.admin.usecase;

import ai.helper2.admin.BulkDecisionRule;
import ai.helper2.catalog.ReviewEvidence;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.usecase.ResolveReviewItem;
import ai.helper2.identity.AuditTrail;
import ai.helper2.platform.BadRequestException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An administrator decides several review items at once (ADR 0026). Each item goes through the
 * one-item decision ({@link ResolveReview}) in its own transaction, so each writes its own audit
 * entry, marked with the batch; one item that fails does not undo the others. An item the bulk rule
 * refuses ({@link BulkDecisionRule}) stays open and is recorded as NOT_ALLOWED. Not transactional
 * itself, on purpose.
 */
public class ResolveReviewItems {

  public static final int MAX_ITEMS = 100;

  /** What happened to one item. */
  public enum Result {
    RESOLVED,
    NOT_OPEN,
    NOT_FOUND,
    NOT_ALLOWED
  }

  public record ItemResult(UUID id, Result result) {}

  /** [batch] marks this decision's audit entries ({@code bulk:<batch>}). */
  public record Outcome(UUID batch, List<ItemResult> items) {}

  private final ReviewQueue queue;
  private final ReviewEvidence evidence;
  private final ResolveReview resolve;
  private final AuditTrail audit;

  public ResolveReviewItems(
      ReviewQueue queue, ReviewEvidence evidence, ResolveReview resolve, AuditTrail audit) {
    this.queue = queue;
    this.evidence = evidence;
    this.resolve = resolve;
    this.audit = audit;
  }

  public Outcome resolve(
      List<UUID> ids, ReviewQueue.Status decision, String adminId, @Nullable String note) {
    requireValid(ids, decision);
    UUID batch = UUID.randomUUID();
    List<ItemResult> results =
        ids.stream()
            .map(id -> new ItemResult(id, decideOne(id, decision, adminId, note, batch)))
            .toList();
    return new Outcome(batch, results);
  }

  private static void requireValid(List<UUID> ids, ReviewQueue.Status decision) {
    if (ids.isEmpty() || ids.size() > MAX_ITEMS) {
      throw new BadRequestException("ids must hold 1 to " + MAX_ITEMS + " items");
    }
    if (ids.stream().distinct().count() != ids.size()) {
      throw new BadRequestException("ids must be unique");
    }
    if (decision != ReviewQueue.Status.ACCEPTED && decision != ReviewQueue.Status.REJECTED) {
      throw new BadRequestException("decision must be ACCEPTED or REJECTED");
    }
  }

  private Result decideOne(
      UUID id, ReviewQueue.Status decision, String adminId, @Nullable String note, UUID batch) {
    Optional<ReviewQueue.Item> item = queue.find(id);
    if (item.isPresent()
        && item.get().status() == ReviewQueue.Status.OPEN
        && !allowed(item.get(), decision)) {
      audit.record(
          adminId,
          AuditTrail.Action.RESOLVE_REVIEW_ITEM,
          id.toString(),
          ResolveReviewItem.inBatch(Result.NOT_ALLOWED.name(), batch));
      return Result.NOT_ALLOWED;
    }
    return switch (resolve.resolve(id, decision, adminId, note, batch)) {
      case ResolveReviewItem.Outcome.Resolved r -> Result.RESOLVED;
      case ResolveReviewItem.Outcome.NotOpen n -> Result.NOT_OPEN;
      case ResolveReviewItem.Outcome.NotFound f -> Result.NOT_FOUND;
    };
  }

  /** Reads the run's conflicts only when they can matter: accepting an enrichment check. */
  private boolean allowed(ReviewQueue.Item item, ReviewQueue.Status decision) {
    List<String> conflicting =
        decision == ReviewQueue.Status.ACCEPTED
                && item.kind() == ReviewQueue.Kind.ENRICHMENT_VALIDATION
            ? evidence.conflictingFields(item.id())
            : List.of();
    return BulkDecisionRule.allows(decision, item.kind(), conflicting);
  }
}
