package ai.helper2.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ai.helper2.api.generated.model.PreferencesChangeDto;
import ai.helper2.identity.CurrentUser;
import ai.helper2.identity.Users;
import ai.helper2.identity.Users.Preferences;
import ai.helper2.identity.usecase.ChangePreferences;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.ProblemType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PreferencesControllerTest {

  private final UUID user = UUID.randomUUID();

  @Test
  void anAccountThatIsGoneIsToldToSignIn() {
    PreferencesController controller =
        new PreferencesController(
            new RequireSignIn(new SignedIn(user)), new ChangePreferences(new NoAccounts()));

    ApiProblem problem =
        catchThrowableOfType(
            ApiProblem.class,
            () -> controller.changePreferences(new PreferencesChangeDto().radarMotion(false)));

    assertThat(problem.type()).isEqualTo(ProblemType.SIGN_IN_REQUIRED);
    assertThat(problem.detail()).isEqualTo("Sign-in required");
    assertThat(problem.getClass()).isEqualTo(ApiProblem.class);
  }

  private record SignedIn(UUID account) implements CurrentUser {

    @Override
    public Optional<UUID> id() {
      return Optional.of(account);
    }

    @Override
    public Optional<String> displayName() {
      return Optional.empty();
    }
  }

  private static final class NoAccounts implements Users {

    @Override
    public Optional<Preferences> preferences(UUID userId) {
      return Optional.empty();
    }

    @Override
    public Optional<Preferences> changeRadarMotion(UUID userId, boolean radarMotion) {
      return Optional.empty();
    }

    @Override
    public Optional<UUID> find(String provider, String subject) {
      throw new UnsupportedOperationException();
    }

    @Override
    public UUID signedIn(String provider, String subject, String displayName) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void delete(UUID userId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int deleteSignedInLastBefore(Instant cutoff) {
      throw new UnsupportedOperationException();
    }
  }
}
