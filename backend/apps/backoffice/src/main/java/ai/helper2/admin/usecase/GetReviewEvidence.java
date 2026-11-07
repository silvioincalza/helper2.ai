package ai.helper2.admin.usecase;

import ai.helper2.catalog.ReviewEvidence;
import ai.helper2.catalog.ReviewQueue;
import java.util.Optional;
import java.util.UUID;

/**
 * An administrator opens a review item and sees what it is about: the run or the relationship it
 * links to, with the quotes behind them (ADR 0026). Read-only. Its read-only transaction is
 * declared where it is wired (AdminUseCaseConfiguration), so the use case stays free of Spring.
 */
public class GetReviewEvidence {

  /** The item and its evidence. */
  public record View(ReviewQueue.Item item, ReviewEvidence.Evidence evidence) {}

  private final ReviewQueue queue;
  private final ReviewEvidence evidence;

  public GetReviewEvidence(ReviewQueue queue, ReviewEvidence evidence) {
    this.queue = queue;
    this.evidence = evidence;
  }

  /**
   * Empty when there is no such item. One read-only transaction: the item and its evidence agree.
   */
  public Optional<View> get(UUID itemId) {
    return queue.find(itemId).map(item -> new View(item, evidence.of(itemId)));
  }
}
