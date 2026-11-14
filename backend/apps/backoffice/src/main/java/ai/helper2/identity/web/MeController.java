package ai.helper2.identity.web;

import ai.helper2.api.common.model.LimitsDto;
import ai.helper2.api.common.model.LimitsWebSearchesDto;
import ai.helper2.api.common.model.MeDto;
import ai.helper2.api.common.model.MeLoginProvidersInnerDto;
import ai.helper2.api.generated.MeApi;
import ai.helper2.identity.CurrentUser;
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

@RestController
@RequestMapping("/api/v1")
class MeController implements MeApi {

  private final CurrentUser currentUser;
  private final ObjectProvider<ClientRegistrationRepository> registrations;
  private final Clock clock;

  MeController(
      CurrentUser currentUser,
      ObjectProvider<ClientRegistrationRepository> registrations,
      Clock clock) {
    this.currentUser = currentUser;
    this.registrations = registrations;
    this.clock = clock;
  }

  @Override
  public ResponseEntity<MeDto> getMe() {
    Authentication a = SecurityContextHolder.getContext().getAuthentication();
    MeDto me =
        new MeDto().authenticated(false).roles(new ArrayList<>()).loginProviders(providers());
    if (a instanceof OAuth2AuthenticationToken t && t.isAuthenticated()) {
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
    return ResponseEntity.ok(me.limits(noLimits()));
  }

  private LimitsDto noLimits() {
    Instant nextUtcMidnight = clock.instant().truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS);
    return new LimitsDto(
        null, null, new LimitsWebSearchesDto(null, null), nextUtcMidnight.atOffset(ZoneOffset.UTC));
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
