package ai.searchavo.identity.web;

import ai.searchavo.identity.CurrentUser;
import ai.searchavo.platform.ApiProblem;
import ai.searchavo.platform.ProblemType;
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
