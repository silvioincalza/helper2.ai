package ai.helper2.admin.usecase;

import ai.helper2.identity.AuditTrail;
import ai.helper2.platform.settings.RuntimeSetting;
import ai.helper2.platform.settings.RuntimeSettingStore;
import ai.helper2.platform.settings.RuntimeSettings;
import java.time.Clock;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An administrator puts a runtime setting back to its default, the H2_ value (ADR 0060). Nothing is
 * deleted: a change with no value is appended, so the history shows the reset too.
 */
@Service
public class ResetRuntimeSetting {

  public sealed interface Outcome {
    record Reset(GetRuntimeSettings.State state) implements Outcome {}

    /** Someone changed the setting first; [current] is what is stored now. */
    record Conflict(GetRuntimeSettings.State current) implements Outcome {}
  }

  private final RuntimeSettingStore store;
  private final GetRuntimeSettings settings;
  private final RuntimeSettings runtime;
  private final AuditTrail audit;
  private final Clock clock;

  public ResetRuntimeSetting(
      RuntimeSettingStore store,
      GetRuntimeSettings settings,
      RuntimeSettings runtime,
      AuditTrail audit,
      Clock clock) {
    this.store = store;
    this.settings = settings;
    this.runtime = runtime;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional
  public Outcome reset(
      String adminId, RuntimeSetting setting, long expectedVersion, @Nullable String reason) {
    GetRuntimeSettings.State before = settings.of(setting);
    String why = ChangeRuntimeSetting.Reasons.clean(reason);
    Outcome outcome = tryReset(adminId, setting, expectedVersion, why);
    audit.record(
        adminId,
        AuditTrail.Action.CHANGE_SETTING,
        setting.key(),
        outcome instanceof Outcome.Reset ? "RESET" : "CONFLICT",
        new AuditTrail.Change(before.described(), backToDefault(before), why));
    return outcome;
  }

  private Outcome tryReset(
      String adminId, RuntimeSetting setting, long expectedVersion, @Nullable String reason) {
    RuntimeSettingStore.Change stored =
        store.append(setting.key(), null, expectedVersion, adminId, clock.instant(), reason);
    if (stored == null) {
      return new Outcome.Conflict(settings.of(setting));
    }
    ChangeRuntimeSetting.applyAfterCommit(runtime);
    return new Outcome.Reset(settings.of(setting));
  }

  /** What a reset leads to, as the audit trail writes it. */
  private static String backToDefault(GetRuntimeSettings.State before) {
    Long value = before.defaultValue();
    return "default " + (value == null ? "no cap" : Long.toString(value));
  }
}
