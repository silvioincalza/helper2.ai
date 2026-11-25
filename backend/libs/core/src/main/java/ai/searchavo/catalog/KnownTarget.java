package ai.searchavo.catalog;

import java.util.Locale;

public record KnownTarget(String name, Kind kind) {

  public enum Kind {
    VENDOR,
    SERVICE,
    TOOL
  }

  public enum Origin {
    CURATED,
    DISCOVERED
  }

  public static final String KEY_OF_TITLE_SQL =
      "regexp_replace(regexp_replace(lower(btrim(%s)), '\\s+', ' ', 'g'), '[''’]s$', '')";

  public static String noLonger(Kind kind) {
    return switch (kind) {
      case VENDOR -> "Its target is no longer a known vendor";
      case SERVICE -> "Its target is no longer a known service";
      case TOOL -> "Its target is no longer a known tool";
    };
  }

  public static String key(String name) {
    return name.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").replaceAll("['’]s$", "");
  }
}
