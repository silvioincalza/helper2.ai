package ai.searchavo.admin.web;

import ai.searchavo.platform.ApiProblem;
import ai.searchavo.platform.ProblemType;

final class KnownTargetProblems {

  private KnownTargetProblems() {}

  static ApiProblem listIsFull(int limit) {
    return new ApiProblem(
        ProblemType.DUPLICATE, "The list holds at most " + limit + " known targets.");
  }
}
