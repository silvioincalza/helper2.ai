package ai.helper2.admin.usecase;

import ai.helper2.platform.settings.RuntimeSetting;
import ai.helper2.platform.settings.RuntimeSettingStore;
import ai.helper2.platform.settings.RuntimeSettings;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The runtime settings as the store holds them (ADR 0060), for the Settings page: each setting's
 * value, default, range, version and last changes. Read from the store, not from this replica's
 * snapshot: the page shows what every replica applies within one refresh interval.
 */
public class GetRuntimeSettings {

  /** Changes listed per setting. */
  static final int HISTORY = 10;

  /**
   * One setting now.
   *
   * @param defaultValue the H2_ value; null = no cap, or no module here uses the setting
   * @param value the value applied: the newest change's, else the default
   * @param version the newest change's version, 0 when it never changed
   */
  public record State(
      RuntimeSetting setting,
      @Nullable Long defaultValue,
      @Nullable Long value,
      boolean overridden,
      boolean inUse,
      long version,
      List<RuntimeSettingStore.Change> history) {

    /** The value as the audit trail writes it: the number, or "default" with the number. */
    public String described() {
      String shown = value == null ? "no cap" : Long.toString(value);
      return overridden ? shown : "default " + shown;
    }
  }

  private final RuntimeSettingStore store;
  private final RuntimeSettings runtime;

  public GetRuntimeSettings(RuntimeSettingStore store, RuntimeSettings runtime) {
    this.store = store;
    this.runtime = runtime;
  }

  /** Every setting of the allow-list, in its order. */
  public List<State> all() {
    return Arrays.stream(RuntimeSetting.values()).map(this::of).toList();
  }

  public State of(RuntimeSetting setting) {
    List<RuntimeSettingStore.Change> history = store.history(setting.key(), HISTORY);
    Long stored = history.isEmpty() ? null : history.getFirst().value();
    Long defaultValue = runtime.defaultValue(setting);
    Long applied = stored == null ? defaultValue : Long.valueOf(Math.min(stored, setting.max()));
    return new State(
        setting,
        defaultValue,
        applied,
        stored != null,
        runtime.declared(setting),
        history.isEmpty() ? 0 : history.getFirst().version(),
        history);
  }
}
