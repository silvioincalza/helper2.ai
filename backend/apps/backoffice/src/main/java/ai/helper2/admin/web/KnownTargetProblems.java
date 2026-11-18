package ai.helper2.admin.web;

import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.ProblemType;

final class KnownTargetProblems {

  private KnownTargetProblems() {}

  static ApiProblem listIsFull(int limit) {
    return new ApiProblem(
        ProblemType.DUPLICATE, "The list holds at most " + limit + " known targets.");
  }
}
