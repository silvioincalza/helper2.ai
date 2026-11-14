package ai.helper2.catalog;

import java.util.Locale;

public record KnownTarget(String name, Kind kind) {

  public enum Kind {
    VENDOR,
    SERVICE
  }

  public enum Origin {
    CURATED,
    DISCOVERED
  }

  public static final String KEY_OF_TITLE_SQL =
      "regexp_replace(regexp_replace(lower(btrim(%s)), '\\s+', ' ', 'g'), '[''’]s$', '')";

  public static String noLonger(Kind kind) {
    return kind == Kind.VENDOR
        ? "Its target is no longer a known vendor"
        : "Its target is no longer a known service";
  }

  public static String key(String name) {
    return name.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").replaceAll("['’]s$", "");
  }
}
