package ai.helper2.admin.web;

import ai.helper2.admin.usecase.ResolveReview;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.BadRequestException;
import ai.helper2.platform.ProblemType;

final class ReviewProblems {

  private ReviewProblems() {}

  static ApiProblem notAdded(ResolveReview.Outcome.NotAdded why) {
    return switch (why) {
      case ResolveReview.Outcome.UnreadableCandidate candidate ->
          new ApiProblem(ProblemType.INVALID_REQUEST, "The candidate cannot be read.");
      case ResolveReview.Outcome.ConfirmedValueRefused value ->
          new BadRequestException("The catalog refuses the confirmed " + value.field() + ".");
      case ResolveReview.Outcome.UnreadableProposal proposal ->
          new ApiProblem(ProblemType.INVALID_REQUEST, "The proposal cannot be read.");
      case ResolveReview.Outcome.InvalidKnownTargetName name ->
          new ApiProblem(
              ProblemType.INVALID_REQUEST, "The proposed name cannot be a known target.");
      case ResolveReview.Outcome.KnownTargetNameTaken taken ->
          new ApiProblem(
              ProblemType.DUPLICATE,
              taken.name() + " already means " + taken.holder() + ". Reject the proposal.");
      case ResolveReview.Outcome.KnownTargetListFull full ->
          KnownTargetProblems.listIsFull(full.limit());
      case ResolveReview.Outcome.UnreadableDraft draft ->
          new ApiProblem(ProblemType.INVALID_REQUEST, "The draft cannot be read.");
      case ResolveReview.Outcome.DuplicateExample duplicate ->
          new ApiProblem(
              ProblemType.DUPLICATE,
              "Another example already has this label or this query. Reject the draft, or change"
                  + " the example first.");
      case ResolveReview.Outcome.InvalidExample invalid ->
          new ApiProblem(ProblemType.INVALID_REQUEST, String.join("; ", invalid.problems()) + ".");
    };
  }
}
