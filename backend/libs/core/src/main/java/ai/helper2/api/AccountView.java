package ai.helper2.api;

import ai.helper2.api.common.model.MeDto;
import ai.helper2.api.common.model.MeLoginProvidersInnerDto;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

public final class AccountView {

  private AccountView() {}

  public static MeDto of(
      Supplier<Optional<String>> displayName,
      @Nullable ClientRegistrationRepository registrations) {
    MeDto me =
        new MeDto()
            .authenticated(false)
            .roles(new ArrayList<>())
            .loginProviders(providers(registrations));
    signedIn()
        .ifPresent(token -> {
          me.authenticated(true).displayName(displayName.get().orElse(null));
          addRoles(me, token);
        });
    return me;
  }

  public static boolean isSignedIn() {
    return signedIn().isPresent();
  }

  public static OffsetDateTime nextUtcMidnight(Clock clock) {
    return clock
        .instant()
        .truncatedTo(ChronoUnit.DAYS)
        .plus(1, ChronoUnit.DAYS)
        .atOffset(ZoneOffset.UTC);
  }

  private static Optional<OAuth2AuthenticationToken> signedIn() {
    Authentication a = SecurityContextHolder.getContext().getAuthentication();
    return a instanceof OAuth2AuthenticationToken t && t.isAuthenticated()
        ? Optional.of(t)
        : Optional.empty();
  }

  private static void addRoles(MeDto me, OAuth2AuthenticationToken token) {
    token
        .getAuthorities()
        .forEach(g -> {
          if ("ROLE_ADMIN".equals(g.getAuthority())) {
            me.addRolesItem(MeDto.RolesEnum.ADMIN);
          } else if ("ROLE_USER".equals(g.getAuthority())) {
            me.addRolesItem(MeDto.RolesEnum.USER);
          }
        });
  }

  private static List<MeLoginProvidersInnerDto> providers(
      @Nullable ClientRegistrationRepository registrations) {
    List<MeLoginProvidersInnerDto> out = new ArrayList<>();
    if (registrations instanceof Iterable<?> it) {
      for (Object o : it) {
        ClientRegistration r = (ClientRegistration) o;
        out.add(new MeLoginProvidersInnerDto(r.getRegistrationId(), r.getClientName()));
      }
    }
    return out;
  }
}
