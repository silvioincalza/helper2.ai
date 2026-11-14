package ai.helper2.identity.web;

import ai.helper2.api.common.model.PreferencesDto;
import ai.helper2.api.generated.PreferencesApi;
import ai.helper2.api.generated.model.PreferencesChangeDto;
import ai.helper2.identity.Users.Preferences;
import ai.helper2.identity.usecase.ChangePreferences;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class PreferencesController implements PreferencesApi {

  private final RequireSignIn signIn;
  private final ChangePreferences changePreferences;

  PreferencesController(RequireSignIn signIn, ChangePreferences changePreferences) {
    this.signIn = signIn;
    this.changePreferences = changePreferences;
  }

  @Override
  public ResponseEntity<PreferencesDto> changePreferences(PreferencesChangeDto body) {
    return ResponseEntity.ok(dto(changePreferences.change(signIn.userId(), body.getRadarMotion())));
  }

  static PreferencesDto dto(Preferences p) {
    return new PreferencesDto(p.radarMotion());
  }
}
