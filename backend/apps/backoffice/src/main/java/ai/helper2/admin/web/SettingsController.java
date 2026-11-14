package ai.helper2.admin.web;

import ai.helper2.admin.usecase.GetEffectiveSettings;
import ai.helper2.admin.usecase.GetEffectiveSettings.AppGroup;
import ai.helper2.admin.usecase.GetEffectiveSettings.EffectiveSettings;
import ai.helper2.api.generated.SettingsApi;
import ai.helper2.api.generated.model.ConfigGroupDto;
import ai.helper2.api.generated.model.ConfigSettingDto;
import ai.helper2.api.generated.model.EffectiveConfigDto;
import ai.helper2.api.generated.model.PublicAppPublicationDto;
import ai.helper2.platform.settings.PublicAppSettings.Publication;
import ai.helper2.platform.settings.Setting;
import java.time.ZoneOffset;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class SettingsController implements SettingsApi {

  private final GetEffectiveSettings settings;

  SettingsController(GetEffectiveSettings settings) {
    this.settings = settings;
  }

  @Override
  public ResponseEntity<EffectiveConfigDto> getEffectiveConfig() {
    EffectiveSettings effective = settings.get();
    return ResponseEntity.ok(
        new EffectiveConfigDto(
            effective.groups().stream().map(SettingsController::dto).toList(),
            effective.publicApp().map(SettingsController::dto).orElse(null)));
  }

  private static PublicAppPublicationDto dto(Publication p) {
    return new PublicAppPublicationDto(p.build(), p.publishedAt().atOffset(ZoneOffset.UTC));
  }

  private static ConfigGroupDto dto(AppGroup g) {
    return new ConfigGroupDto(
        g.group().id(),
        g.group().title(),
        ConfigGroupDto.AppEnum.fromValue(g.app().name()),
        g.group().settings().stream().map(SettingsController::dto).toList());
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
