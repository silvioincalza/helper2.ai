package ai.searchavo.identity.internal;

import ai.searchavo.platform.ApiProblem;
import ai.searchavo.platform.ProblemType;

public final class SignInAgain extends ApiProblem {

  public static final String WHY = "This session has no account. Sign in again.";

  public SignInAgain() {
    super(ProblemType.SIGN_IN_AGAIN, WHY);
  }
}
