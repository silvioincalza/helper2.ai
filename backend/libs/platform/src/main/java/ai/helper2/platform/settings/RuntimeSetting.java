package ai.helper2.platform.settings;

import java.util.Arrays;
import java.util.Optional;

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
      2_000,
      "H2_LIVE_WEB_DAILY_CREDITS",
      "Live web calls a UTC day for all of helper2.ai. 0: no live call, stored results only.");

  public static final long MIN = 0;

  private final String key;
  private final String label;
  private final String unit;
  private final long max;
  private final String variable;
  private final String help;

  RuntimeSetting(String key, String label, String unit, long max, String variable, String help) {
    this.key = key;
    this.label = label;
    this.unit = unit;
    this.max = max;
    this.variable = variable;
    this.help = help;
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
