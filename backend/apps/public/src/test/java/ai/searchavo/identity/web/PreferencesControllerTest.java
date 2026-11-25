package ai.searchavo.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ai.searchavo.api.generated.model.PreferencesChangeDto;
import ai.searchavo.identity.CurrentUser;
import ai.searchavo.identity.Users;
import ai.searchavo.identity.Users.Preferences;
import ai.searchavo.identity.usecase.ChangePreferences;
import ai.searchavo.platform.ApiProblem;
import ai.searchavo.platform.ProblemType;
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
