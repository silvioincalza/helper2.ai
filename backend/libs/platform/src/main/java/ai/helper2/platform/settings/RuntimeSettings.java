package ai.helper2.platform.settings;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

public final class RuntimeSettings {
  record Snapshot(long version, Map<RuntimeSetting, Long> overrides) {
    static final Snapshot EMPTY = new Snapshot(0, Map.of());
  }

  private record Default(@Nullable Long value) {}

  private final RuntimeSettingStore store;
  private final Clock clock;
  private final Duration refresh;
  private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.EMPTY);
  private final Map<RuntimeSetting, Default> defaults = new ConcurrentHashMap<>();
  private volatile Instant nextCheck = Instant.MIN;

  public RuntimeSettings(RuntimeSettingStore store, Clock clock, Duration refresh) {
    this.store = store;
    this.clock = clock;
    this.refresh = refresh;
  }

  public IntSupplier declare(RuntimeSetting setting, int defaultValue) {
    register(setting, (long) defaultValue);
    return () -> {
      Long value = current(setting);
      return value == null ? defaultValue : (int) Math.min(value, Integer.MAX_VALUE);
    };
  }

  public Supplier<Long> declareCap(RuntimeSetting setting, @Nullable Long defaultValue) {
    long finiteDefault = defaultValue == null ? setting.max() : defaultValue;
    register(setting, finiteDefault);
    return () -> Objects.requireNonNull(current(setting));
  }

  private void register(RuntimeSetting setting, @Nullable Long defaultValue) {
    if (defaultValue != null && !setting.allows(defaultValue)) {
      throw new IllegalStateException(
          setting.variable()
              + " is "
              + defaultValue
              + ", outside 0 to "
              + setting.max()
              + ", the code maximum of "
              + setting.key());
    }
    Default previous = defaults.putIfAbsent(setting, new Default(defaultValue));
    if (previous != null && !Objects.equals(previous.value(), defaultValue)) {
      throw new IllegalStateException(setting.key() + " declared twice with two defaults");
    }
  }

  public @Nullable Long current(RuntimeSetting setting) {
    Long override = snapshot.get().overrides().get(setting);
    return override != null ? override : defaultValue(setting);
  }

  public @Nullable Long override(RuntimeSetting setting) {
    return snapshot.get().overrides().get(setting);
  }

  public @Nullable Long defaultValue(RuntimeSetting setting) {
    Default d = defaults.get(setting);
    return d == null ? null : d.value();
  }

  public boolean declared(RuntimeSetting setting) {
    return defaults.containsKey(setting);
  }

  public int overrideCount() {
    return snapshot.get().overrides().size();
  }

  public long version() {
    return snapshot.get().version();
  }

  public boolean tick() {
    Instant now = clock.instant();
    if (now.isBefore(nextCheck)) {
      return false;
    }
    nextCheck = now.plus(refresh);
    return reloadIfChanged();
  }

  public synchronized boolean reloadIfChanged() {
    Snapshot applied = snapshot.get();
    if (store.version() == applied.version()) {
      return false;
    }
    Snapshot next = read();
    return snapshot.compareAndSet(applied, next);
  }

  private Snapshot read() {
    Map<String, RuntimeSettingStore.Change> newest = store.newest();
    Map<RuntimeSetting, Long> overrides = new EnumMap<>(RuntimeSetting.class);
    long version = 0;
    for (RuntimeSettingStore.Change c : newest.values()) {
      version = Math.max(version, c.version());
      RuntimeSetting.byKey(c.name())
          .filter(s -> c.value() != null)
          .ifPresent(s -> overrides.put(s, Math.min(c.value(), s.max())));
    }
    return new Snapshot(version, Map.copyOf(overrides));
  }
}
