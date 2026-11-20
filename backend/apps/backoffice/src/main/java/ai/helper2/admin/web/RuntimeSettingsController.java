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
import ai.helper2.api.generated.model.RuntimeSwitchChangeDto;
import ai.helper2.api.generated.model.RuntimeSwitchHistoryEntryDto;
import ai.helper2.api.generated.model.RuntimeSwitchStateDto;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.BadRequestException;
import ai.helper2.platform.NotFoundException;
import ai.helper2.platform.ProblemType;
import ai.helper2.platform.settings.RuntimeSetting;
import ai.helper2.platform.settings.RuntimeSettingStore;
import ai.helper2.shared.UtcTimes;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    List<GetRuntimeSettings.State> limits = settings.of(RuntimeSetting.Kind.LIMIT);
    int overrides = (int) limits.stream().filter(GetRuntimeSettings.State::overridden).count();
    return ResponseEntity.ok(
        new RuntimeSettingsDto(
            overrides,
            limits.stream().map(RuntimeSettingsController::dto).toList(),
            settings.of(RuntimeSetting.Kind.SWITCH).stream()
                .map(RuntimeSettingsController::switchDto)
                .toList()));
  }

  @Override
  public ResponseEntity<RuntimeSettingStateDto> changeRuntimeSetting(
      String key, RuntimeSettingChangeDto body) {
    RuntimeSetting setting = known(key);
    return switch (change.change(
        Admins.id(), setting, body.getValue(), body.getVersion(), body.getReason())) {
      case ChangeRuntimeSetting.Outcome.Changed c -> ResponseEntity.ok(dto(c.state()));
      case ChangeRuntimeSetting.Outcome.OutOfRange r ->
          throw new ApiProblem(
              ProblemType.INVALID_REQUEST,
              "value: must be from 0 to " + setting.max() + " " + setting.unit() + ".");
      case ChangeRuntimeSetting.Outcome.Conflict c -> throw conflict();
      case ChangeRuntimeSetting.Outcome.InvalidReason i -> throw invalidReason();
    };
  }

  @Override
  public ResponseEntity<RuntimeSettingStateDto> resetRuntimeSetting(
      String key, RuntimeSettingResetDto body) {
    return switch (reset.reset(Admins.id(), known(key), body.getVersion(), body.getReason())) {
      case ResetRuntimeSetting.Outcome.Reset r -> ResponseEntity.ok(dto(r.state()));
      case ResetRuntimeSetting.Outcome.Conflict c -> throw conflict();
      case ResetRuntimeSetting.Outcome.InvalidReason i -> throw invalidReason();
    };
  }

  @Override
  public ResponseEntity<RuntimeSwitchStateDto> changeRuntimeSwitch(
      String key, RuntimeSwitchChangeDto body) {
    RuntimeSetting setting = knownSwitch(key);
    long value = RuntimeSetting.valueOf(body.getOn());
    return switch (change.change(
        Admins.id(), setting, value, body.getVersion(), body.getReason())) {
      case ChangeRuntimeSetting.Outcome.Changed c -> ResponseEntity.ok(switchDto(c.state()));
      case ChangeRuntimeSetting.Outcome.OutOfRange r ->
          throw new ApiProblem(ProblemType.INVALID_REQUEST, "on: must be true or false.");
      case ChangeRuntimeSetting.Outcome.Conflict c -> throw conflict();
      case ChangeRuntimeSetting.Outcome.InvalidReason i -> throw invalidReason();
    };
  }

  @Override
  public ResponseEntity<RuntimeSwitchStateDto> resetRuntimeSwitch(
      String key, RuntimeSettingResetDto body) {
    return switch (reset.reset(
        Admins.id(), knownSwitch(key), body.getVersion(), body.getReason())) {
      case ResetRuntimeSetting.Outcome.Reset r -> ResponseEntity.ok(switchDto(r.state()));
      case ResetRuntimeSetting.Outcome.Conflict c -> throw conflict();
      case ResetRuntimeSetting.Outcome.InvalidReason i -> throw invalidReason();
    };
  }

  private static RuntimeSetting known(String key) {
    return RuntimeSetting.byKey(key)
        .filter(s -> !s.isSwitch())
        .orElseThrow(() -> new NotFoundException("No runtime setting " + key + "."));
  }

  private static RuntimeSetting knownSwitch(String key) {
    return RuntimeSetting.byKey(key)
        .filter(RuntimeSetting::isSwitch)
        .orElseThrow(() -> new NotFoundException("No runtime switch " + key + "."));
  }

  private static BadRequestException invalidReason() {
    return new BadRequestException("reason: no control or format characters");
  }

  private static ApiProblem conflict() {
    return new ApiProblem(
        ProblemType.VERSION_CONFLICT,
        "Someone changed this setting first. Load it again and repeat the change.");
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

  private static RuntimeSwitchStateDto switchDto(GetRuntimeSettings.State s) {
    RuntimeSetting r = s.setting();
    return new RuntimeSwitchStateDto(
        r.key(),
        r.label(),
        r.help(),
        r.variable(),
        on(s.defaultValue()),
        on(s.value()),
        s.overridden(),
        s.inUse(),
        Math.toIntExact(s.version()),
        s.history().stream().map(RuntimeSettingsController::switchDto).toList());
  }

  private static RuntimeSwitchHistoryEntryDto switchDto(RuntimeSettingStore.Change c) {
    return new RuntimeSwitchHistoryEntryDto(
        Math.toIntExact(c.version()),
        c.value() == null ? null : c.value() > 0,
        c.changedBy(),
        UtcTimes.odt(c.changedAt()),
        c.reason());
  }

  private static boolean on(Long value) {
    return value != null && value > 0;
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
