package ai.helper2.platform.settings;

import java.util.Arrays;
import java.util.Optional;

/**
 * The settings an administrator may change at runtime (ADR 0058): a few spending limits, and
 * nothing else. The allow-list is this enum: a secret, an admin list, a model id, a prompt, a token
 * limit, a ranking weight, a rate limit or a per-visitor quota has no constant here, so it cannot
 * be changed from the back office.
 *
 * <p>The {@code H2_} variable is the default, not a ceiling. {@link #max} is the absolute maximum,
 * in code: going above it needs a pull request. The minimum is 0, which turns the use off.
 *
 * @param key the setting's key on the Settings page ({@link Setting#key()})
 * @param label what the page shows
 * @param unit the unit of the value
 * @param max the absolute maximum, in code
 * @param variable the {@code H2_} variable that sets the default
 * @param help one line of help on the page
 */
public enum RuntimeSetting {
  SCOUT_MAX_ENRICHMENTS_PER_RUN(
      "scout.maxEnrichmentsPerRun",
      "Enrichments a run",
      "calls a run",
      500,
      "H2_SCOUT_MAX_ENRICHMENTS",
      "Most resources one Scout run enriches, collectors and backlog together. Read at the start of a"
          + " run. 0: no enrichment."),
  SCOUT_DAILY_ENRICHMENT_CALLS(
      "scout.dailyEnrichmentCalls",
      "Enrichment calls a day",
      "calls a day",
      2_000,
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
      "Tavily credits a day, all of Helper2",
      "credits a day",
      2_000,
      "H2_TAVILY_GLOBAL_DAILY_CREDITS",
      "Live Tavily calls a UTC day for all of Helper2. 0: no live call, stored results only.");

  /** The lowest value of every runtime setting: 0 turns the use off. */
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

  /** Whether [value] is inside the allowed range, 0 to {@link #max}. */
  public boolean allows(long value) {
    return value >= MIN && value <= max;
  }

  public static Optional<RuntimeSetting> byKey(String key) {
    return Arrays.stream(values()).filter(s -> s.key.equals(key)).findFirst();
  }
}
