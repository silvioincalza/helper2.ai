package ai.helper2.identity;

import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.ProblemType;

public final class SignInAgain extends ApiProblem {

  public static final String WHY = "This session has no account. Sign in again.";

  public SignInAgain() {
    super(ProblemType.SIGN_IN_AGAIN, WHY);
  }
}
