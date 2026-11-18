package ai.helper2.admin.usecase;

import ai.helper2.catalog.usecase.ResolveReviewItem;
import ai.helper2.scout.usecase.ListedResource;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class DecideReviewItem {

  public sealed interface Decided {
    record Resolved(ReviewItemView item, @Nullable ListedResource alreadyListed)
        implements Decided {}

    record NotOpen(ReviewItemView.Status status) implements Decided {}

    record NotFound() implements Decided {}
  }

  private final ResolveReview resolve;
  private final ReviewItemViews views;

  public DecideReviewItem(ResolveReview resolve, ReviewItemViews views) {
    this.resolve = resolve;
    this.views = views;
  }

  public Decided decide(
      UUID id, ReviewItemView.Status decision, String adminId, @Nullable String note) {
    ResolveReview.Decision decided = resolve.decide(id, decision.inQueue(), adminId, note);
    return switch (decided.outcome()) {
      case ResolveReviewItem.Outcome.Resolved r ->
          new Decided.Resolved(views.of(r.item()), decided.alreadyListed());
      case ResolveReviewItem.Outcome.NotOpen n ->
          new Decided.NotOpen(ReviewItemView.Status.of(n.item().status()));
      case ResolveReviewItem.Outcome.NotFound f -> new Decided.NotFound();
    };
  }
}
