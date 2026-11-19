package ai.helper2.admin.usecase;

import ai.helper2.catalog.usecase.GetReviewItem;
import java.util.Optional;
import java.util.UUID;

public class ShowReviewItem {

  private final GetReviewItem item;
  private final ReviewItemViews views;

  public ShowReviewItem(GetReviewItem item, ReviewItemViews views) {
    this.item = item;
    this.views = views;
  }

  public Optional<ReviewItemView> get(UUID id) {
    return item.get(id).map(views::of);
  }
}
