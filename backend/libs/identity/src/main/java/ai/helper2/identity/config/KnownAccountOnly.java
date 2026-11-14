package ai.helper2.identity.config;

import ai.helper2.identity.CurrentUser;
import ai.helper2.identity.SignInAgain;
import ai.helper2.platform.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CompositeLogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

public final class KnownAccountOnly extends OncePerRequestFilter {

  private final CurrentUser currentUser;
  private final RequestMatcher needsTheAccount;
  private final LogoutHandler endSession;

  public KnownAccountOnly(
      CurrentUser currentUser, RequestMatcher needsTheAccount, CsrfTokenRepository csrfTokens) {
    this.currentUser = currentUser;
    this.needsTheAccount = needsTheAccount;
    this.endSession =
        new CompositeLogoutHandler(
            new SecurityContextLogoutHandler(), new CsrfLogoutHandler(csrfTokens));
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !needsTheAccount.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      currentUser.id();
    } catch (SignInAgain noAccount) {
      endSession.logout(request, response, SecurityContextHolder.getContext().getAuthentication());
      Problems.write(response, noAccount.type(), noAccount.detail());
      return;
    }
    chain.doFilter(request, response);
  }
}
