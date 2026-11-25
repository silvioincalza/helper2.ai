package ai.searchavo.admin.usecase;

import ai.searchavo.catalog.ReviewQueue;
import ai.searchavo.catalog.usecase.ListReviewItems;
import ai.searchavo.research.ResearchFilter;
import ai.searchavo.research.usecase.FindResearchedItems;
import java.util.List;
import org.jspecify.annotations.Nullable;

public class ListReviewQueue {

  public record Page(List<ReviewItemView> items, long total) {}

  private final ListReviewItems items;
  private final FindResearchedItems researched;
  private final ReviewItemViews views;

  public ListReviewQueue(
      ListReviewItems items, FindResearchedItems researched, ReviewItemViews views) {
    this.items = items;
    this.researched = researched;
    this.views = views;
  }

  public Page list(
      ReviewItemView.@Nullable Status status,
      ReviewQueue.@Nullable Kind kind,
      @Nullable String category,
      @Nullable ResearchFilter research,
      int page,
      int size) {
    ReviewQueue.Page found =
        items.list(
            status == null ? null : status.inQueue(), kind, category, only(research), page, size);
    return new Page(found.items().stream().map(views::of).toList(), found.total());
  }

  private ReviewQueue.@Nullable Only only(@Nullable ResearchFilter research) {
    return research == null
        ? null
        : new ReviewQueue.Only(researched.ids(research), research.excludes());
  }
}
