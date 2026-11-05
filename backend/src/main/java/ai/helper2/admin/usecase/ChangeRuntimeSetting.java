package ai.helper2.admin.usecase;

import ai.helper2.identity.AuditTrail;
import ai.helper2.platform.settings.RuntimeSetting;
import ai.helper2.platform.settings.RuntimeSettingStore;
import ai.helper2.platform.settings.RuntimeSettings;
import java.time.Clock;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * An administrator sets a runtime setting's value (ADR 0058). The value must be in the setting's
 * range, and the change must be made on the setting's newest version. The change and its audit row
 * are written in one transaction; this replica applies it right after the commit, the others within
 * one refresh interval.
 */
@Service
public class ChangeRuntimeSetting {

  public sealed interface Outcome {
    record Changed(GetRuntimeSettings.State state) implements Outcome {}

    record OutOfRange(RuntimeSetting setting) implements Outcome {}

    /** Someone changed the setting first; [current] is what is stored now. */
    record Conflict(GetRuntimeSettings.State current) implements Outcome {}
  }

  private final RuntimeSettingStore store;
  private final GetRuntimeSettings settings;
  private final RuntimeSettings runtime;
  private final AuditTrail audit;
  private final Clock clock;

  public ChangeRuntimeSetting(
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
  public Outcome change(
      String adminId,
      RuntimeSetting setting,
      long value,
      long expectedVersion,
      @Nullable String reason) {
    GetRuntimeSettings.State before = settings.of(setting);
    String why = Reasons.clean(reason);
    Outcome outcome = tryChange(adminId, setting, value, expectedVersion, why);
    audit.record(
        adminId,
        AuditTrail.Action.CHANGE_SETTING,
        setting.key(),
        recorded(outcome),
        new AuditTrail.Change(before.described(), Long.toString(value), why));
    return outcome;
  }

  private Outcome tryChange(
      String adminId,
      RuntimeSetting setting,
      long value,
      long expectedVersion,
      @Nullable String reason) {
    if (!setting.allows(value)) {
      return new Outcome.OutOfRange(setting);
    }
    RuntimeSettingStore.Change stored =
        store.append(setting.key(), value, expectedVersion, adminId, clock.instant(), reason);
    if (stored == null) {
      return new Outcome.Conflict(settings.of(setting));
    }
    applyAfterCommit(runtime);
    return new Outcome.Changed(settings.of(setting));
  }

  private static String recorded(Outcome outcome) {
    return switch (outcome) {
      case Outcome.Changed c -> "CHANGED";
      case Outcome.OutOfRange r -> "OUT_OF_RANGE";
      case Outcome.Conflict c -> "CONFLICT";
    };
  }

  /** This replica reads the settings again once the change is committed, not before. */
  static void applyAfterCommit(RuntimeSettings runtime) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      runtime.reloadIfChanged();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              runtime.reloadIfChanged();
            } catch (RuntimeException e) {
              // The refresh thread reads it within one interval.
            }
          }
        });
  }

  /** The administrator's reason: trimmed, and none when blank. */
  static final class Reasons {
    private Reasons() {}

    static @Nullable String clean(@Nullable String reason) {
      return reason == null || reason.isBlank() ? null : reason.strip();
    }
  }
}
