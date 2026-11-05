package ai.helper2.admin.web;

import ai.helper2.admin.usecase.ChangeRuntimeSetting;
import ai.helper2.admin.usecase.GetRuntimeSettings;
import ai.helper2.admin.usecase.ResetRuntimeSetting;
import ai.helper2.api.generated.RuntimeSettingsApi;
import ai.helper2.api.generated.model.RuntimeSettingChangeDto;
import ai.helper2.api.generated.model.RuntimeSettingHistoryEntryDto;
import ai.helper2.api.generated.model.RuntimeSettingResetDto;
import ai.helper2.api.generated.model.RuntimeSettingStateDto;
import ai.helper2.api.generated.model.RuntimeSettingsDto;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.NotFoundException;
import ai.helper2.platform.ProblemType;
import ai.helper2.platform.settings.RuntimeSetting;
import ai.helper2.platform.settings.RuntimeSettingStore;
import ai.helper2.shared.UtcTimes;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The runtime settings (ADR 0058), ADMIN only, admin port. */
@RestController
@RequestMapping("/api/v1")
class RuntimeSettingsController implements RuntimeSettingsApi {

  private final GetRuntimeSettings settings;
  private final ChangeRuntimeSetting change;
  private final ResetRuntimeSetting reset;

  RuntimeSettingsController(
      GetRuntimeSettings settings, ChangeRuntimeSetting change, ResetRuntimeSetting reset) {
    this.settings = settings;
    this.change = change;
    this.reset = reset;
  }

  @Override
  public ResponseEntity<RuntimeSettingsDto> listRuntimeSettings() {
    List<GetRuntimeSettings.State> all = settings.all();
    int overrides = (int) all.stream().filter(GetRuntimeSettings.State::overridden).count();
    return ResponseEntity.ok(
        new RuntimeSettingsDto(
            overrides, all.stream().map(RuntimeSettingsController::dto).toList()));
  }

  @Override
  public ResponseEntity<RuntimeSettingStateDto> changeRuntimeSetting(
      String key, RuntimeSettingChangeDto body) {
    RuntimeSetting setting = known(key);
    return switch (change.change(
        adminId(), setting, body.getValue(), body.getVersion(), body.getReason())) {
      case ChangeRuntimeSetting.Outcome.Changed c -> ResponseEntity.ok(dto(c.state()));
      case ChangeRuntimeSetting.Outcome.OutOfRange r ->
          throw new ApiProblem(
              ProblemType.INVALID_REQUEST,
              "value: must be from 0 to " + setting.max() + " " + setting.unit() + ".");
      case ChangeRuntimeSetting.Outcome.Conflict c -> throw conflict();
    };
  }

  @Override
  public ResponseEntity<RuntimeSettingStateDto> resetRuntimeSetting(
      String key, RuntimeSettingResetDto body) {
    return switch (reset.reset(adminId(), known(key), body.getVersion(), body.getReason())) {
      case ResetRuntimeSetting.Outcome.Reset r -> ResponseEntity.ok(dto(r.state()));
      case ResetRuntimeSetting.Outcome.Conflict c -> throw conflict();
    };
  }

  private static RuntimeSetting known(String key) {
    return RuntimeSetting.byKey(key)
        .orElseThrow(() -> new NotFoundException("No runtime setting " + key + "."));
  }

  private static ApiProblem conflict() {
    return new ApiProblem(
        ProblemType.VERSION_CONFLICT,
        "Someone changed this setting first. Load it again and repeat the change.");
  }

  /** The signed-in administrator's provider subject (GitHub: the numeric user id). */
  private static String adminId() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }

  private static RuntimeSettingStateDto dto(GetRuntimeSettings.State s) {
    RuntimeSetting r = s.setting();
    return new RuntimeSettingStateDto(
        r.key(),
        r.label(),
        r.unit(),
        r.help(),
        (int) RuntimeSetting.MIN,
        (int) r.max(),
        r.variable(),
        toInt(s.defaultValue()),
        toInt(s.value()),
        s.overridden(),
        s.inUse(),
        Math.toIntExact(s.version()),
        s.history().stream().map(RuntimeSettingsController::dto).toList());
  }

  private static RuntimeSettingHistoryEntryDto dto(RuntimeSettingStore.Change c) {
    return new RuntimeSettingHistoryEntryDto(
        Math.toIntExact(c.version()),
        toInt(c.value()),
        c.changedBy(),
        UtcTimes.odt(c.changedAt()),
        c.reason());
  }

  private static Integer toInt(Long value) {
    return value == null ? null : Math.toIntExact(value);
  }
}
