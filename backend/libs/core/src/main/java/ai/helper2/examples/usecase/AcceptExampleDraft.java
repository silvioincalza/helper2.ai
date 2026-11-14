package ai.helper2.examples.usecase;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.examples.DraftQueue;
import ai.helper2.examples.ExampleOrigin;
import ai.helper2.examples.WaitingDraft;
import java.util.List;

public class AcceptExampleDraft {

  private final DraftQueue drafts;
  private final AddHomeExample add;

  public AcceptExampleDraft(DraftQueue drafts, AddHomeExample add) {
    this.drafts = drafts;
    this.add = add;
  }

  public sealed interface Outcome {
    record Added(ExampleView example) implements Outcome {}

    record Unreadable() implements Outcome {}

    record Invalid(List<String> problems) implements Outcome {}

    record Duplicate() implements Outcome {}
  }

  public Outcome accept(String adminId, ReviewQueue.Item item) {
    WaitingDraft draft =
        item.kind() == ReviewQueue.Kind.EXAMPLE_CANDIDATE ? drafts.read(item.details()) : null;
    if (draft == null || item.resourceId() == null) {
      return new Outcome.Unreadable();
    }
    return switch (add.add(
        adminId,
        draft.getLabel(),
        draft.getQuery(),
        draft.getPath(),
        List.of(item.resourceId()),
        ExampleOrigin.AI_DRAFTED,
        draft.getModel())) {
      case AddHomeExample.Outcome.Added a -> new Outcome.Added(a.example());
      case AddHomeExample.Outcome.Invalid i -> new Outcome.Invalid(i.problems());
      case AddHomeExample.Outcome.Duplicate d -> new Outcome.Duplicate();
    };
  }
}
