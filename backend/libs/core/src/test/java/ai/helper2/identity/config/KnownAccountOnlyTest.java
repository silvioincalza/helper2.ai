package ai.helper2.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.identity.CurrentUser;
import ai.helper2.identity.SignInAgain;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

class KnownAccountOnlyTest {

  static final class Account implements CurrentUser {
    boolean missing;

    @Override
    public Optional<UUID> id() {
      if (missing) {
        throw new SignInAgain();
      }
      return Optional.of(UUID.randomUUID());
    }

    @Override
    public Optional<String> displayName() {
      return Optional.of("Someone");
    }
  }

  final Account account = new Account();
  final KnownAccountOnly filter =
      new KnownAccountOnly(
          account,
          PathPatternRequestMatcher.withDefaults().matcher("/api/**"),
          CookieCsrfTokenRepository.withHttpOnlyFalse());
  final MockHttpSession session = new MockHttpSession();
  final MockHttpServletResponse response = new MockHttpServletResponse();
  final MockFilterChain chain = new MockFilterChain();

  MockHttpServletRequest signedIn(String path) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setSession(session);
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("42", null, List.of()));
    return request;
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void aSessionWithoutAnAccountIsEndedAndToldToSignInAgain() throws Exception {
    account.missing = true;

    filter.doFilter(signedIn("/api/v1/me"), response, chain);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentType()).isEqualTo("application/problem+json");
    assertThat(response.getContentAsString())
        .contains("\"code\":\"sign-in-again\"")
        .contains("https://helper2.ai/problems/sign-in-again");
    assertThat(session.isInvalid()).isTrue();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(response.getCookie("XSRF-TOKEN").getMaxAge()).isZero();
    assertThat(chain.getRequest()).as("the request stops here").isNull();
  }

  @Test
  void aSessionWithItsAccountGoesOn() throws Exception {
    filter.doFilter(signedIn("/api/v1/me"), response, chain);

    assertThat(chain.getRequest()).isNotNull();
    assertThat(session.isInvalid()).isFalse();
  }

  @Test
  void aPathThatNeedsNoAccountIsNotChecked() throws Exception {
    account.missing = true;

    filter.doFilter(signedIn("/assets/app.js"), response, chain);

    assertThat(chain.getRequest()).isNotNull();
    assertThat(session.isInvalid()).isFalse();
  }
}
