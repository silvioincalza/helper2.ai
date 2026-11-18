package ai.helper2.scout.internal;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.scout.usecase.CandidateEdit;
import ai.helper2.scout.usecase.CandidateItem;
import ai.helper2.scout.usecase.CandidateItems;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
class ReviewQueueCandidateItems implements CandidateItems {

  private static final int MAX_REASON = 300;

  private final ReviewQueue queue;
  private final ResourceCandidateJson json;

  ReviewQueueCandidateItems(ReviewQueue queue, ResourceCandidateJson json) {
    this.queue = queue;
    this.json = json;
  }

  @Override
  public @Nullable CandidateItem find(UUID itemId) {
    return queue
        .find(itemId)
        .map(i ->
            new CandidateItem(i.status() == ReviewQueue.Status.OPEN, structured(i), i.details()))
        .orElse(null);
  }

  @Override
  public String edit(@Nullable String details, CandidateEdit edit) {
    return json.edit(details, edit);
  }

  @Override
  public boolean replace(UUID itemId, @Nullable String oldDetails, String newDetails) {
    String reason = json.reason(newDetails);
    return queue.updateCandidate(
        itemId,
        oldDetails,
        reason.length() > MAX_REASON ? reason.substring(0, MAX_REASON) : reason,
        newDetails);
  }

  static boolean structured(ReviewQueue.Item item) {
    return item.kind() == ReviewQueue.Kind.RESOURCE_CANDIDATE
        || (item.kind() == ReviewQueue.Kind.COURSE_CANDIDATE
            && item.details() != null
            && item.details().strip().startsWith("{"));
  }
}
