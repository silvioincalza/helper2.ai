package ai.helper2.identity.web;

import ai.helper2.api.generated.MeApi;
import ai.helper2.api.generated.model.LimitsDto;
import ai.helper2.api.generated.model.LimitsWebSearchesDto;
import ai.helper2.api.generated.model.MeDto;
import ai.helper2.api.generated.model.MeLoginProvidersInnerDto;
import ai.helper2.api.generated.model.MonetisationDto;
import ai.helper2.identity.internal.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/me on the back office: who is signed in, with which roles, and the sign-in providers.
 * The admin web app reads it when it starts, and this call sets the CSRF cookie. No visitor limits
 * and no monetisation here: those are the public site's (ADR 0057, 0069).
 */
@RestController
@RequestMapping("/api/v1")
class MeController implements MeApi {

  private final ObjectProvider<ClientRegistrationRepository> registrations;
  private final Clock clock;

  MeController(ObjectProvider<ClientRegistrationRepository> registrations, Clock clock) {
    this.registrations = registrations;
    this.clock = clock;
  }

  @Override
  public ResponseEntity<MeDto> getMe() {
    Authentication a = SecurityContextHolder.getContext().getAuthentication();
    MeDto me =
        new MeDto().authenticated(false).roles(new ArrayList<>()).loginProviders(providers());
    if (a instanceof OAuth2AuthenticationToken t && t.isAuthenticated()) {
      me.authenticated(true).displayName(CurrentUser.displayName(t));
      t.getAuthorities()
          .forEach(g -> {
            if ("ROLE_ADMIN".equals(g.getAuthority())) {
              me.addRolesItem(MeDto.RolesEnum.ADMIN);
            } else if ("ROLE_USER".equals(g.getAuthority())) {
              me.addRolesItem(MeDto.RolesEnum.USER);
            }
          });
    }
    return ResponseEntity.ok(me.limits(noLimits()).monetisation(NO_MONETISATION));
  }

  /** Nothing of the site's funding on the admin host. */
  static final MonetisationDto NO_MONETISATION =
      new MonetisationDto(null, null, null, null, null, null);

  /** The visitor limits are the public site's; the contract still names the next reset. */
  private LimitsDto noLimits() {
    Instant nextUtcMidnight = clock.instant().truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS);
    return new LimitsDto(
        null, new LimitsWebSearchesDto(null, null), nextUtcMidnight.atOffset(ZoneOffset.UTC));
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
