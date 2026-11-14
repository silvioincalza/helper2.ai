package ai.helper2.examples.usecase;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.examples.DraftQueue;
import ai.helper2.examples.WaitingDraft;
import java.util.Optional;

public class ReadExampleDraft {

  private final DraftQueue drafts;

  public ReadExampleDraft(DraftQueue drafts) {
    this.drafts = drafts;
  }

  public Optional<WaitingDraft> in(ReviewQueue.Item item) {
    if (item.kind() != ReviewQueue.Kind.EXAMPLE_CANDIDATE) {
      return Optional.empty();
    }
    return Optional.ofNullable(drafts.read(item.details()));
  }
}
