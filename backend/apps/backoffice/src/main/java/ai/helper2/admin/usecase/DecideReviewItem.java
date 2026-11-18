package ai.helper2.admin.usecase;

import ai.helper2.scout.usecase.ListedResource;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class DecideReviewItem {

  public sealed interface Decided {
    record Resolved(ReviewItemView item, @Nullable ListedResource alreadyListed)
        implements Decided {}

    record NotOpen(ReviewItemView.Status status) implements Decided {}

    record NotFound() implements Decided {}

    record NotAdded(ResolveReview.Outcome.NotAdded why) implements Decided {}
  }

  private final ResolveReview resolve;
  private final ReviewItemViews views;

  public DecideReviewItem(ResolveReview resolve, ReviewItemViews views) {
    this.resolve = resolve;
    this.views = views;
  }

  public Decided decide(
      UUID id, ReviewItemView.Status decision, String adminId, @Nullable String note) {
    return switch (resolve.resolve(id, decision.inQueue(), adminId, note, null)) {
      case ResolveReview.Outcome.Resolved r ->
          new Decided.Resolved(views.of(r.item()), r.alreadyListed());
      case ResolveReview.Outcome.NotOpen n ->
          new Decided.NotOpen(ReviewItemView.Status.of(n.item().status()));
      case ResolveReview.Outcome.NotFound f -> new Decided.NotFound();
      case ResolveReview.Outcome.NotAdded why -> new Decided.NotAdded(why);
    };
  }
}
