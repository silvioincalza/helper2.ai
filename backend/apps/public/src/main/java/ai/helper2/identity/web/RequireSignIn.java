package ai.helper2.identity.web;

import ai.helper2.identity.CurrentUser;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.ProblemType;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class RequireSignIn {

  private final CurrentUser currentUser;

  RequireSignIn(CurrentUser currentUser) {
    this.currentUser = currentUser;
  }

  UUID userId() {
    return currentUser.id().orElseThrow(() -> new ApiProblem(ProblemType.SIGN_IN_REQUIRED, null));
  }
}
