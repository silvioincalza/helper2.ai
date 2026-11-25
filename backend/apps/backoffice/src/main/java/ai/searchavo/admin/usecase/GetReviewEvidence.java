package ai.searchavo.admin.usecase;

import ai.searchavo.catalog.AwesomeListEntries;
import ai.searchavo.catalog.DirectoryRating;
import ai.searchavo.catalog.ResourceRatings;
import ai.searchavo.catalog.ReviewEvidence;
import ai.searchavo.catalog.ReviewQueue;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class GetReviewEvidence {
  public record View(
      ReviewItemView item,
      ReviewEvidence.@Nullable Run run,
      List<String> validationErrors,
      List<ReviewEvidence.Field> fields,
      ReviewEvidence.@Nullable Relationship relationship,
      boolean aiWritten,
      List<AwesomeListEntries.DirectoryList> directoryLists,
      List<DirectoryRating.Stated> directoryRatings) {}

  private final ReviewQueue queue;
  private final ReviewEvidence evidence;
  private final ReviewItemViews views;
  private final AwesomeListEntries listEntries;
  private final ResourceRatings ratings;

  public GetReviewEvidence(
      ReviewQueue queue,
      ReviewEvidence evidence,
      ReviewItemViews views,
      AwesomeListEntries listEntries,
      ResourceRatings ratings) {
    this.ratings = ratings;
    this.queue = queue;
    this.evidence = evidence;
    this.views = views;
    this.listEntries = listEntries;
  }

  public Optional<View> get(UUID itemId) {
    return queue.find(itemId).map(item -> view(item, evidence.of(itemId)));
  }

  private View view(ReviewQueue.Item item, ReviewEvidence.Evidence e) {
    return new View(
        views.of(item),
        e.run(),
        e.validationErrors(),
        e.fields(),
        e.relationship(),
        e.aiWritten(),
        directoryLists(item),
        directoryRatings(item));
  }

  // UC-024 BR-020: the back office names the directories whose figures make the rating.
  private List<DirectoryRating.Stated> directoryRatings(ReviewQueue.Item item) {
    return item.resourceId() == null ? List.of() : ratings.stated(item.resourceId());
  }

  // UC-024 BR-019: the back office names the directory lists that name the item's resource.
  private List<AwesomeListEntries.DirectoryList> directoryLists(ReviewQueue.Item item) {
    return item.resourceId() == null
        ? List.of()
        : listEntries.directoryListsNaming(item.resourceId());
  }
}
