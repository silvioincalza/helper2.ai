package ai.helper2.identity.usecase;

import ai.helper2.identity.Users;
import ai.helper2.identity.Users.Preferences;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.ProblemType;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A signed-in user changes their display settings. A setting left out stays as it is; today the
 * only one is whether the Home radar moves between its sources (ADR 0028).
 */
public class ChangePreferences {

  private final Users users;

  public ChangePreferences(Users users) {
    this.users = users;
  }

  public Preferences change(UUID userId, @Nullable Boolean radarMotion) {
    var changed =
        radarMotion == null
            ? users.preferences(userId)
            : users.changeRadarMotion(userId, radarMotion);
    return changed.orElseThrow(() -> new ApiProblem(ProblemType.SIGN_IN_REQUIRED, null));
  }
}
