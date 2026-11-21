package ai.helper2.platform.settings;

import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public enum RuntimeSetting {
  SCOUT_MAX_ENRICHMENTS_PER_RUN(
      "scout.maxEnrichmentsPerRun",
      "Enrichments a run",
      "calls a run",
      3_000,
      "H2_SCOUT_MAX_ENRICHMENTS",
      "Most resources one Scout run enriches, collectors and backlog together. Read at the start of a"
          + " run. 0: no enrichment."),
  SCOUT_DAILY_ENRICHMENT_CALLS(
      "scout.dailyEnrichmentCalls",
      "Enrichment calls a day",
      "calls a day",
      3_000,
      "H2_SCOUT_DAILY_ENRICHMENT_CALLS",
      "The hard cap on enrichment model calls a UTC day, gap rounds included. 0: enrichment off."),
  SCOUT_GAP_DAILY_CALLS(
      "scout.gapDailyCalls",
      "Gap rounds a day",
      "calls a day",
      2_000,
      "H2_SCOUT_GAP_DAILY_CALLS",
      "Gap rounds a UTC day, a share of the enrichment calls, not on top. 0: gap rounds off."),
  GUIDE_DAILY_MODEL_CALLS(
      "guide.dailyModelCalls",
      "Model calls a day, all visitors",
      "calls a day",
      3_000,
      "H2_GUIDE_DAILY_MODEL_CALLS",
      "The AI guide's model calls a UTC day for everyone. 0: answers come from search only."),
  SEARCH_DAILY_TRANSLATIONS(
      "search.dailyTranslations",
      "Translations a day",
      "calls a day",
      2_000,
      "H2_SEARCH_DAILY_TRANSLATIONS",
      "Query translations a UTC day. 0: queries run as typed."),
  SEARCH_WEB_GLOBAL_DAILY_CREDITS(
      "search.webGlobalDailyCredits",
      "Live web credits a day, all of helper2.ai",
      "credits a day",
      3_000,
      "H2_LIVE_WEB_DAILY_CREDITS",
      "Live web calls a UTC day for all of helper2.ai. 0: no live call, stored results only."),
  SCOUT_SEARCH_DAILY_CREDITS(
      "scoutSources.dailyCredits",
      "Live web credits a day, Scout searches",
      "credits a day",
      3_000,
      "H2_SCOUT_DAILY_CREDITS",
      "Live web credits the Scout's searches and list reads spend a UTC day. They count on the live"
          + " web credits of all of helper2.ai too. 0: no Scout search."),
  SCOUT_PAGE_READ_DAILY_CREDITS(
      "scout.evidenceDailyExtractCredits",
      "Page reads a day, product pages",
      "credits a day",
      3_000,
      "H2_SCOUT_PAGE_READER_DAILY_CREDITS",
      "Page-read credits a UTC day for product pages. Each one also takes a live web credit of all of"
          + " helper2.ai. 0: no product page is read."),
  REVIEW_RESEARCH_DAILY_MODEL_CALLS(
      "research.dailyModelCalls",
      "Research model calls a day",
      "calls a day",
      20_000,
      "H2_RESEARCH_DAILY_MODEL_CALLS",
      "Model calls of the review research agent a UTC day. A run takes up to 14; with fewer left,"
          + " 0 included, research waits."),
  ADS_ENABLED(
      "ads.enabled",
      "Google ads",
      "H2_ADS_ENABLED",
      "On: one Google ad in each place whose ad unit is set (resource page, search results, guide"
          + " answer). Off: no Google ad anywhere; the site check tag and /ads.txt stay.");

  public enum Kind {
    LIMIT,
    SWITCH
  }

  public static final long MIN = 0;

  private final Kind kind;
  private final String key;
  private final String label;
  private final String unit;
  private final long max;
  private final String variable;
  private final String help;

  RuntimeSetting(String key, String label, String unit, long max, String variable, String help) {
    this(Kind.LIMIT, key, label, unit, max, variable, help);
  }

  RuntimeSetting(String key, String label, String variable, String help) {
    this(Kind.SWITCH, key, label, "", 1, variable, help);
  }

  RuntimeSetting(
      Kind kind, String key, String label, String unit, long max, String variable, String help) {
    this.kind = kind;
    this.key = key;
    this.label = label;
    this.unit = unit;
    this.max = max;
    this.variable = variable;
    this.help = help;
  }

  public Kind kind() {
    return kind;
  }

  public boolean isSwitch() {
    return kind == Kind.SWITCH;
  }

  public static long valueOf(boolean on) {
    return on ? 1 : 0;
  }

  public String shown(@Nullable Long value) {
    if (value == null) {
      return "no cap";
    }
    return isSwitch() ? Boolean.toString(value > 0) : Long.toString(value);
  }

  public String key() {
    return key;
  }

  public String label() {
    return label;
  }

  public String unit() {
    return unit;
  }

  public long max() {
    return max;
  }

  public String variable() {
    return variable;
  }

  public String help() {
    return help;
  }

  public boolean allows(long value) {
    return value >= MIN && value <= max;
  }

  public static Optional<RuntimeSetting> byKey(String key) {
    return Arrays.stream(values()).filter(s -> s.key.equals(key)).findFirst();
  }
}
