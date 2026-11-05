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

/**
 * The runtime settings as this replica applies them (ADR 0058). Hot paths read one immutable
 * snapshot, never the database. The snapshot is read again when the store's version changes,
 * checked at most once per refresh interval, so every replica applies a change within about that
 * long. A replica that cannot read the store keeps its last snapshot.
 *
 * <p>The module that uses a setting declares its default (the {@code H2_} value) and gets a
 * supplier of the current value: the override when there is one, else the default.
 */
public final class RuntimeSettings {

  /** What the replica applies: the overrides of the store's version [version]. */
  record Snapshot(long version, Map<RuntimeSetting, Long> overrides) {
    static final Snapshot EMPTY = new Snapshot(0, Map.of());
  }

  /** A declared default; its value is null for "no cap". */
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

  /** A whole-number limit with its default; the supplier reads the snapshot only. */
  public IntSupplier declare(RuntimeSetting setting, int defaultValue) {
    register(setting, (long) defaultValue);
    return () -> {
      Long value = current(setting);
      return value == null ? defaultValue : (int) Math.min(value, Integer.MAX_VALUE);
    };
  }

  /**
   * A daily cap whose {@code H2_} value may be empty ("no cap"). A runtime setting is always
   * finite: an empty value applies as the code maximum, so neither a reset nor an unset variable
   * removes the cap.
   */
  public Supplier<Long> declareCap(RuntimeSetting setting, @Nullable Long defaultValue) {
    long finiteDefault = defaultValue == null ? setting.max() : defaultValue;
    register(setting, finiteDefault);
    return () -> Objects.requireNonNull(current(setting));
  }

  /**
   * The default is the {@code H2_} value. One above the code maximum stops startup: a higher
   * maximum needs a change in {@link RuntimeSetting}.
   */
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

  /** The value applied now: the override, else the default; null = no cap, or not declared. */
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

  /** Whether a module of this replica uses the setting. */
  public boolean declared(RuntimeSetting setting) {
    return defaults.containsKey(setting);
  }

  /** How many settings have an override now. */
  public int overrideCount() {
    return snapshot.get().overrides().size();
  }

  /** The store's version this replica applies. */
  public long version() {
    return snapshot.get().version();
  }

  /**
   * Checks the store's version when the refresh interval has passed since the last check, and reads
   * the settings again when it changed. True when new values were read.
   */
  public boolean tick() {
    Instant now = clock.instant();
    if (now.isBefore(nextCheck)) {
      return false;
    }
    nextCheck = now.plus(refresh);
    return reloadIfChanged();
  }

  /**
   * Reads the settings again when the store's version is not the one applied (after a change, or a
   * store restored from a backup: what it holds now wins). The refresh thread and a request that
   * made a change can both call it, so reloads run one at a time; a read never replaces a snapshot
   * that a later read applied while it ran (only the thread that holds the lock can, re-entrant).
   */
  public synchronized boolean reloadIfChanged() {
    Snapshot applied = snapshot.get();
    if (store.version() == applied.version()) {
      return false;
    }
    Snapshot next = read();
    return snapshot.compareAndSet(applied, next);
  }

  /**
   * The newest change of each setting in the allow-list. A stored value above today's code maximum
   * (the maximum was lowered by a later release) applies as the maximum.
   */
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
