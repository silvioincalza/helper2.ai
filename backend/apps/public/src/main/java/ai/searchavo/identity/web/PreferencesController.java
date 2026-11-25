package ai.searchavo.identity.web;

import ai.searchavo.api.common.model.PreferencesDto;
import ai.searchavo.api.generated.PreferencesApi;
import ai.searchavo.api.generated.model.PreferencesChangeDto;
import ai.searchavo.identity.Users.Preferences;
import ai.searchavo.identity.usecase.ChangePreferences;
import ai.searchavo.platform.ApiProblem;
import ai.searchavo.platform.ProblemType;
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
    return switch (changePreferences.change(signIn.userId(), body.getRadarMotion())) {
      case ChangePreferences.Outcome.Changed changed ->
          ResponseEntity.ok(dto(changed.preferences()));
      case ChangePreferences.Outcome.NoSuchUser none ->
          throw new ApiProblem(ProblemType.SIGN_IN_REQUIRED, null);
    };
  }

  static PreferencesDto dto(Preferences p) {
    return new PreferencesDto(p.radarMotion());
  }
}
