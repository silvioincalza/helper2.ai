package ai.helper2.admin.web;

import ai.helper2.admin.usecase.GetEffectiveSettings;
import ai.helper2.api.generated.SettingsApi;
import ai.helper2.api.generated.model.ConfigGroupDto;
import ai.helper2.api.generated.model.ConfigSettingDto;
import ai.helper2.api.generated.model.EffectiveConfigDto;
import ai.helper2.platform.settings.Setting;
import ai.helper2.platform.settings.SettingsGroup;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/v1/admin/config (ADMIN only, admin port): the Settings page, read-only. */
@RestController
@RequestMapping("/api/v1")
class SettingsController implements SettingsApi {

  private final GetEffectiveSettings settings;

  SettingsController(GetEffectiveSettings settings) {
    this.settings = settings;
  }

  @Override
  public ResponseEntity<EffectiveConfigDto> getEffectiveConfig() {
    return ResponseEntity.ok(
        new EffectiveConfigDto(settings.get().stream().map(SettingsController::dto).toList()));
  }

  private static ConfigGroupDto dto(SettingsGroup group) {
    return new ConfigGroupDto(
        group.id(), group.title(), group.settings().stream().map(SettingsController::dto).toList());
  }

  private static ConfigSettingDto dto(Setting s) {
    return new ConfigSettingDto(
        s.key(),
        s.label(),
        ConfigSettingDto.KindEnum.fromValue(s.kind().name()),
        s.value(),
        s.unit(),
        s.variable());
  }
}
