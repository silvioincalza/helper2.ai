package ai.helper2.platform.settings;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record Setting(
    String key,
    String label,
    Kind kind,
    @Nullable String value,
    @Nullable String unit,
    @Nullable String variable) {
  public enum Kind {
    COUNT,
    CAP,
    FLAG,
    DURATION,
    MODEL,
    VERSION,
    SCHEDULE,
    NAMES,
  }

  static final int MAX_LENGTH = 500;

  public static final String HIDDEN = "(hidden)";

  private static final Logger log = LoggerFactory.getLogger(Setting.class);

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

  static Setting published(
      String key,
      String label,
      Kind kind,
      @Nullable String value,
      @Nullable String unit,
      @Nullable String variable) {
    return new Setting(key, label, kind, value, unit, variable);
  }

  public static Setting count(
      String key, String label, long value, String unit, @Nullable String variable) {
    return new Setting(key, label, Kind.COUNT, Long.toString(value), unit, variable);
  }

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

  public static Setting model(
      String key, String label, @Nullable String modelId, @Nullable String variable) {
    String id = modelId == null ? "" : modelId.strip();
    boolean none = id.isEmpty() || id.toLowerCase(Locale.ROOT).equals("none");
    return new Setting(key, label, Kind.MODEL, none ? null : id, null, variable);
  }

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
