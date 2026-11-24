package ai.searchavo.admin.web;

import ai.searchavo.admin.usecase.ResolveReview;
import ai.searchavo.platform.ApiProblem;
import ai.searchavo.platform.ProblemType;

final class ReviewProblems {

  private ReviewProblems() {}

  static ApiProblem notAdded(ResolveReview.Outcome.NotAdded why) {
    return switch (why) {
      case ResolveReview.Outcome.UnreadableCandidate candidate ->
          new ApiProblem(ProblemType.INVALID_REQUEST, "The candidate cannot be read.");
      case ResolveReview.Outcome.ConfirmedValueRefused value ->
          new ApiProblem(
              ProblemType.INVALID_REQUEST,
              "The catalog refuses the confirmed "
                  + value.field()
                  + ". "
                  + CuratedFieldProblems.reason(value.why())
                  + " Edit the candidate, then accept again.");
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
