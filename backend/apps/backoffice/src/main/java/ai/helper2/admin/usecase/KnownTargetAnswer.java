package ai.helper2.admin.usecase;

import ai.helper2.catalog.usecase.ManageKnownTargets;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.ProblemType;
import java.util.function.Consumer;

public final class KnownTargetAnswer {

  private KnownTargetAnswer() {}

  public static ApiProblem listIsFull() {
    return new ApiProblem(
        ProblemType.DUPLICATE,
        "The list holds at most " + ManageKnownTargets.MAX_TARGETS + " known targets.");
  }

  static void requireAdded(
      ManageKnownTargets.Saved saved,
      Consumer<ManageKnownTargets.Saved.Taken> whenTaken,
      String invalidName) {
    switch (saved) {
      case ManageKnownTargets.Saved.Done d -> {}
      case ManageKnownTargets.Saved.Taken t -> whenTaken.accept(t);
      case ManageKnownTargets.Saved.Full f -> throw listIsFull();
      case ManageKnownTargets.Saved.Invalid i ->
          throw new ApiProblem(ProblemType.INVALID_REQUEST, invalidName);
      case ManageKnownTargets.Saved.NotFound n ->
          throw new IllegalStateException("adding a known target cannot miss one");
    }
  }
}
