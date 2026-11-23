package ai.helper2.admin.web;

import ai.helper2.catalog.usecase.CurateField;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.ProblemType;

final class CuratedFieldProblems {

  private CuratedFieldProblems() {}

  static ApiProblem refused(CurateField.Outcome.ValueRefused why) {
    return new ApiProblem(ProblemType.INVALID_REQUEST, reason(why));
  }

  static String reason(CurateField.Outcome.ValueRefused why) {
    return switch (why) {
      case CurateField.Outcome.UnknownField unknown ->
          "This field cannot be set: " + unknown.field() + ".";
      case CurateField.Outcome.ValueNotAllowed value ->
          "This value is not allowed for " + value.field() + ".";
      case CurateField.Outcome.InvalidDescription description ->
          "The description must be 1 to " + description.maxLength() + " characters.";
      case CurateField.Outcome.NotADate notADate -> "The published date must be YYYY-MM-DD.";
      case CurateField.Outcome.DateInTheFuture future -> "The published date is in the future.";
    };
  }
}
