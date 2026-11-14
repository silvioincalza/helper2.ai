package ai.helper2.admin.usecase;

import ai.helper2.platform.settings.RuntimeSetting;
import ai.helper2.platform.settings.RuntimeSettingStore;
import ai.helper2.platform.settings.RuntimeSettings;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

public class GetRuntimeSettings {
  static final int HISTORY = 10;

  public record State(
      RuntimeSetting setting,
      @Nullable Long defaultValue,
      @Nullable Long value,
      boolean overridden,
      boolean inUse,
      long version,
      List<RuntimeSettingStore.Change> history) {
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
