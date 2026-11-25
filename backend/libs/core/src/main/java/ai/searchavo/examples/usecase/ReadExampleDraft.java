package ai.searchavo.examples.usecase;

import ai.searchavo.catalog.ReviewQueue;
import ai.searchavo.examples.DraftQueue;
import ai.searchavo.examples.WaitingDraft;
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
