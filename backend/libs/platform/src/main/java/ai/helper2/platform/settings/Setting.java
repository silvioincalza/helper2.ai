package ai.helper2.platform.settings;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One setting the back office shows read-only (ADR 0045): what the app runs with now. Built only
 * through the factories below, one per kind of value, so a module lists each setting by hand from
 * its typed record. A secret has no factory: there is no way to show one by mistake. A value the
 * page cannot show safely is replaced by {@value #HIDDEN} and logged by key, never by value: a
 * valid setting such as the cron {@code @daily} must not stop the app (SettingFactoriesTest keeps
 * {@code new Setting(} inside this record).
 *
 * @param key a stable id, {@code <group>.<name>}
 * @param label what the page shows, in plain words
 * @param kind how the page reads [value]
 * @param value the value as text; null means "no cap" for a CAP and "none" for a MODEL or VERSION
 * @param unit the unit of a COUNT or CAP ("calls a day"); null for the other kinds
 * @param variable the {@code H2_} variable that sets it; null when the code fixes it
 */
public record Setting(
    String key,
    String label,
    Kind kind,
    @Nullable String value,
    @Nullable String unit,
    @Nullable String variable) {

  /** How the page reads a value. */
  public enum Kind {
    /** A whole number, 0 or more. */
    COUNT,
    /** A daily cap: a whole number, or null for no cap. */
    CAP,
    /** "true" or "false". */
    FLAG,
    /** Seconds. */
    DURATION,
    /** A model id. */
    MODEL,
    /** A prompt version or a configuration hash. */
    VERSION,
    /** A Spring cron expression, UTC. */
    SCHEDULE,
    /** Names, comma-separated. */
    NAMES,
  }

  /** Longest value shown; a longer one is hidden. */
  static final int MAX_LENGTH = 500;

  /** What the page shows in place of a value it cannot show safely. */
  public static final String HIDDEN = "(hidden)";

  private static final Logger log = LoggerFactory.getLogger(Setting.class);

  /**
   * What a value may hold: letters, digits and the punctuation of model ids, cron expressions and
   * names. No quote, no angle bracket, no {@code @}: a URL with a user or a password is hidden.
   */
  private static final Pattern SAFE = Pattern.compile("[\\p{Alnum} ._:/,*?#+()\\-]*");

  public Setting {
    if (value != null && !isSafe(value)) {
      log.warn("Setting {} has a value the back office cannot show; it shows {}", key, HIDDEN);
      value = HIDDEN;
    }
  }

  private static boolean isSafe(String value) {
    return value.length() <= MAX_LENGTH && SAFE.matcher(value).matches();
  }

  public static Setting count(
      String key, String label, long value, String unit, @Nullable String variable) {
    return new Setting(key, label, Kind.COUNT, Long.toString(value), unit, variable);
  }

  /** A count fixed in code, with no variable. */
  public static Setting fixedCount(String key, String label, long value, String unit) {
    return new Setting(key, label, Kind.COUNT, Long.toString(value), unit, null);
  }

  public static Setting cap(
      String key, String label, @Nullable Long value, String unit, @Nullable String variable) {
    return new Setting(
        key, label, Kind.CAP, value == null ? null : Long.toString(value), unit, variable);
  }

  public static Setting flag(String key, String label, boolean value, @Nullable String variable) {
    return new Setting(key, label, Kind.FLAG, Boolean.toString(value), null, variable);
  }

  public static Setting duration(
      String key, String label, Duration value, @Nullable String variable) {
    return new Setting(key, label, Kind.DURATION, Long.toString(value.toSeconds()), null, variable);
  }

  /** A model id; blank or "none" means no model. */
  public static Setting model(
      String key, String label, @Nullable String modelId, @Nullable String variable) {
    String id = modelId == null ? "" : modelId.strip();
    boolean none = id.isEmpty() || id.toLowerCase(Locale.ROOT).equals("none");
    return new Setting(key, label, Kind.MODEL, none ? null : id, null, variable);
  }

  /** A prompt version or a configuration hash, fixed by the code. */
  public static Setting version(String key, String label, @Nullable String version) {
    String v = version == null || version.isBlank() ? null : version.strip();
    return new Setting(key, label, Kind.VERSION, v, null, null);
  }

  public static Setting schedule(String key, String label, String cron, @Nullable String variable) {
    return new Setting(key, label, Kind.SCHEDULE, cron.strip(), null, variable);
  }

  public static Setting names(
      String key, String label, List<String> names, @Nullable String variable) {
    return new Setting(
        key, label, Kind.NAMES, String.join(",", names.stream().sorted().toList()), null, variable);
  }
}
