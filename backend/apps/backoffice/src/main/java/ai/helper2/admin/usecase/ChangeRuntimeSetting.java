package ai.helper2.admin.usecase;

import ai.helper2.platform.AuditTrail;
import ai.helper2.platform.settings.RuntimeSetting;
import ai.helper2.platform.settings.RuntimeSettingStore;
import ai.helper2.platform.settings.RuntimeSettings;
import java.time.Clock;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public class ChangeRuntimeSetting {

  public sealed interface Outcome {
    sealed interface Attempted extends Outcome {}

    record Changed(GetRuntimeSettings.State state) implements Attempted {}

    record OutOfRange(RuntimeSetting setting) implements Attempted {}

    record Conflict(GetRuntimeSettings.State current) implements Attempted {}

    record InvalidReason() implements Outcome {}
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

  public Outcome change(
      String adminId,
      RuntimeSetting setting,
      long value,
      long expectedVersion,
      @Nullable String reason) {
    if (!Reasons.isPlain(reason)) {
      return new Outcome.InvalidReason();
    }
    GetRuntimeSettings.State before = settings.of(setting);
    String why = Reasons.clean(reason);
    Outcome.Attempted outcome = tryChange(adminId, setting, value, expectedVersion, why);
    audit.record(
        adminId,
        AuditTrail.Action.CHANGE_SETTING,
        setting.key(),
        recorded(outcome),
        new AuditTrail.Change(before.described(), Long.toString(value), why));
    return outcome;
  }

  private Outcome.Attempted tryChange(
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

  private static String recorded(Outcome.Attempted outcome) {
    return switch (outcome) {
      case Outcome.Changed c -> "CHANGED";
      case Outcome.OutOfRange r -> "OUT_OF_RANGE";
      case Outcome.Conflict c -> "CONFLICT";
    };
  }

  protected void applyAfterCommit(RuntimeSettings runtime) {
    runtime.reloadIfChanged();
  }

  static final class Reasons {
    private static final Pattern HIDDEN_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Cf}]");

    private Reasons() {}

    static boolean isPlain(@Nullable String reason) {
      return reason == null || !HIDDEN_CHARACTERS.matcher(reason).find();
    }

    static @Nullable String clean(@Nullable String reason) {
      return reason == null || reason.isBlank() ? null : reason.strip();
    }
  }
}
