package ai.helper2.admin.usecase;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.usecase.ListReviewItems;
import ai.helper2.research.ResearchFilter;
import ai.helper2.research.usecase.FindResearchedItems;
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
