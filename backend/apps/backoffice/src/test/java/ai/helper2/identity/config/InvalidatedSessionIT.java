package ai.helper2.identity.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ai.helper2.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InvalidatedSessionIT {

  @Autowired SessionRepository<?> sessions;

  @Test
  void aRequestThatEndsAfterSignInReplacedItsSessionSavesNothingAndDoesNotFail() {
    savingAfterInvalidation(sessions);
  }

  private static <S extends Session> void savingAfterInvalidation(SessionRepository<S> sessions) {
    S session = sessions.createSession();
    sessions.save(session);
    S heldByASlowRequest = sessions.findById(session.getId());

    sessions.deleteById(session.getId());
    heldByASlowRequest.setAttribute("seen", "yes");

    assertThat(sessions).isInstanceOf(SessionConfiguration.FailSoftSessionRepository.class);
    assertThatCode(() -> sessions.save(heldByASlowRequest)).doesNotThrowAnyException();
    assertThat(sessions.findById(session.getId())).isNull();
  }
}
