package ai.helper2.identity.web;

import ai.helper2.api.common.model.DailyAllowanceDto;
import ai.helper2.api.common.model.LimitsDto;
import ai.helper2.api.common.model.LimitsWebSearchesDto;
import ai.helper2.api.common.model.MeDto;
import ai.helper2.api.common.model.MeLoginProvidersInnerDto;
import ai.helper2.api.common.model.WebSearchesDto;
import ai.helper2.api.generated.MeApi;
import ai.helper2.identity.CurrentUser;
import ai.helper2.identity.VisitorLimits;
import ai.helper2.identity.usecase.DeleteMyAccount;
import ai.helper2.identity.usecase.ShowPreferences;
import ai.helper2.platform.ClientKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class MeController implements MeApi {

  private final CurrentUser currentUser;
  private final RequireSignIn signIn;
  private final DeleteMyAccount deleteMyAccount;
  private final ShowPreferences showPreferences;
  private final ObjectProvider<ClientRegistrationRepository> registrations;
  private final VisitorLimits limits;
  private final Clock clock;
  private final HttpServletRequest request;
  private final HttpServletResponse response;

  MeController(
      CurrentUser currentUser,
      RequireSignIn signIn,
      DeleteMyAccount deleteMyAccount,
      ShowPreferences showPreferences,
      ObjectProvider<ClientRegistrationRepository> registrations,
      VisitorLimits limits,
      Clock clock,
      HttpServletRequest request,
      HttpServletResponse response) {
    this.currentUser = currentUser;
    this.signIn = signIn;
    this.deleteMyAccount = deleteMyAccount;
    this.showPreferences = showPreferences;
    this.registrations = registrations;
    this.limits = limits;
    this.clock = clock;
    this.request = request;
    this.response = response;
  }

  @Override
  public ResponseEntity<MeDto> getMe() {
    Authentication a = SecurityContextHolder.getContext().getAuthentication();
    MeDto me =
        new MeDto().authenticated(false).roles(new ArrayList<>()).loginProviders(providers());
    if (a instanceof OAuth2AuthenticationToken t && t.isAuthenticated()) {
      currentUser
          .id()
          .flatMap(showPreferences::of)
          .ifPresent(p -> me.preferences(PreferencesController.dto(p)));
      me.authenticated(true).displayName(currentUser.displayName().orElse(null));
      t.getAuthorities()
          .forEach(g -> {
            if ("ROLE_ADMIN".equals(g.getAuthority())) {
              me.addRolesItem(MeDto.RolesEnum.ADMIN);
            } else if ("ROLE_USER".equals(g.getAuthority())) {
              me.addRolesItem(MeDto.RolesEnum.USER);
            }
          });
    }
    return ResponseEntity.ok(me.limits(limitsToday()));
  }

  private LimitsDto limitsToday() {
    VisitorLimits.Today today = limits.of(ClientKey.of(request.getRemoteAddr()));
    Instant nextUtcMidnight = clock.instant().truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS);
    return new LimitsDto(
        allowance(today.guideQuestions()),
        allowance(today.setupGuides()),
        new LimitsWebSearchesDto(
            webSearches(today.searchWebSearches()), webSearches(today.guideWebSearches())),
        nextUtcMidnight.atOffset(ZoneOffset.UTC));
  }

  private static @Nullable DailyAllowanceDto allowance(VisitorLimits.@Nullable Allowance a) {
    return a == null ? null : new DailyAllowanceDto(a.limit(), a.remaining());
  }

  private static @Nullable WebSearchesDto webSearches(VisitorLimits.@Nullable Allowance a) {
    return a == null ? null : new WebSearchesDto(a.limit(), a.remaining());
  }

  @Override
  public ResponseEntity<Void> deleteMyAccount() {
    UUID userId = signIn.userId();
    new SecurityContextLogoutHandler()
        .logout(request, response, SecurityContextHolder.getContext().getAuthentication());
    deleteMyAccount.delete(userId);
    return ResponseEntity.noContent().build();
  }

  private List<MeLoginProvidersInnerDto> providers() {
    List<MeLoginProvidersInnerDto> out = new ArrayList<>();
    ClientRegistrationRepository repo = registrations.getIfAvailable();
    if (repo instanceof Iterable<?> it) {
      for (Object o : it) {
        ClientRegistration r = (ClientRegistration) o;
        out.add(new MeLoginProvidersInnerDto(r.getRegistrationId(), r.getClientName()));
      }
    }
    return out;
  }
}
