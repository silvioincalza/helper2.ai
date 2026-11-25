package ai.searchavo.identity.web;

import ai.searchavo.api.AccountView;
import ai.searchavo.api.common.model.LimitsDto;
import ai.searchavo.api.common.model.LimitsWebSearchesDto;
import ai.searchavo.api.common.model.MeDto;
import ai.searchavo.api.generated.MeApi;
import ai.searchavo.identity.CurrentUser;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
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
    MeDto me = AccountView.of(currentUser::displayName, registrations.getIfAvailable());
    return ResponseEntity.ok(me.limits(noLimits()));
  }

  private LimitsDto noLimits() {
    return new LimitsDto(
        null, null, new LimitsWebSearchesDto(null, null), AccountView.nextUtcMidnight(clock));
  }
}
