package ai.helper2.platform.settings;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: the changes of the runtime settings (ADR 0007), append-only. The current value of
 * a setting is its newest change; a change with no value means "back to the default". A setting's
 * version is the version of its newest change, 0 when it never changed.
 */
public interface RuntimeSettingStore {

  /**
   * One change.
   *
   * @param version grows with every change of any setting
   * @param value the new value; null = back to the default
   */
  record Change(
      long version,
      String name,
      @Nullable Long value,
      String changedBy,
      Instant changedAt,
      @Nullable String reason) {}

  /** The newest version of all settings together, 0 when none ever changed; one cheap read. */
  long version();

  /** The newest change of each setting, by name. */
  Map<String, Change> newest();

  /** The changes of one setting, newest first, at most [limit]. */
  List<Change> history(String name, int limit);

  /**
   * Appends a change when the setting's version is still [expectedVersion]; one change at a time
   * across replicas. Returns the stored change, or null when someone changed the setting first.
   */
  @Nullable Change append(
      String name,
      @Nullable Long value,
      long expectedVersion,
      String changedBy,
      Instant at,
      @Nullable String reason);

  /** Deletes changes older than [cutoff], except each setting's newest; returns how many. */
  int deleteBefore(Instant cutoff);
}
