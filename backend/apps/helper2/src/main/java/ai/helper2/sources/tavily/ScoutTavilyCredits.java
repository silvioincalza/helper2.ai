package ai.helper2.sources.tavily;

import ai.helper2.platform.quota.QuotaCounter;
import ai.helper2.platform.quota.QuotaKeys;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * The Tavily credits the Scout's searches spend: discovery (ADR 0051) and LinkedIn (ADR 0061). Each
 * search adds its credits to the day's global credit counter, the one the live search counts on and
 * the Sources page shows, and to the Scout's own counter, which has its own daily cap: past it
 * these collectors skip until the next UTC day. The credits metric tells them apart by its feature
 * tag. A search costs what Tavily's {@code usage.credits} says, or 2 credits for advanced depth and
 * 1 for basic when the answer does not say.
 *
 * <p>When the counter cannot be read, the cap counts as reached (no credits spent unguarded); when
 * it cannot be written, the search is kept and the credits are only logged.
 */
public class ScoutTavilyCredits {

  private static final Logger log = LoggerFactory.getLogger(ScoutTavilyCredits.class);

  /**
   * The feature tag of discovery's credits, beside the live search's "search" and "guide" and
   * LinkedIn's "scout-linkedin".
   */
  static final String FEATURE = "scout-discovery";

  private final QuotaCounter counter;
  private final QuotaKeys keys;
  private final long dailyCap;
  private final MeterRegistry meters;
  private final String feature;

  public ScoutTavilyCredits(
      QuotaCounter counter, QuotaKeys keys, long dailyCap, MeterRegistry meters) {
    this(counter, keys, dailyCap, meters, FEATURE);
  }

  private ScoutTavilyCredits(
      QuotaCounter counter, QuotaKeys keys, long dailyCap, MeterRegistry meters, String feature) {
    this.counter = counter;
    this.keys = keys;
    this.dailyCap = dailyCap;
    this.meters = meters;
    this.feature = feature;
  }

  /** The same counters and cap, with [feature] as the metric's feature tag ("scout-linkedin"). */
  public ScoutTavilyCredits forFeature(String feature) {
    return new ScoutTavilyCredits(counter, keys, dailyCap, meters, feature);
  }

  public long dailyCap() {
    return dailyCap;
  }

  /** True when today's Scout credits reached the cap, or the counter cannot say. */
  public boolean capReached() {
    try {
      return counter.used(scout()) >= dailyCap;
    } catch (RuntimeException e) {
      log.warn("Scout Tavily credits unreadable: {}", e.getClass().getSimpleName());
      return true;
    }
  }

  /** Counts the credits of one search that answered with [root], at [searchDepth]. */
  public long spent(JsonNode root, String searchDepth) {
    long credits = creditsOf(root, searchDepth);
    meters
        .counter("helper2.live-web.credits", "feature", feature, "tier", "none")
        .increment(credits);
    try {
      counter.add(new QuotaCounter.Counter(keys.global(), QuotaKeys.CREDITS), credits);
      counter.add(scout(), credits);
    } catch (RuntimeException e) {
      log.warn("Scout Tavily credits not counted: {}", e.getClass().getSimpleName());
    }
    return credits;
  }

  /** {@code usage.credits} when Tavily sends it; else 2 for advanced and 1 for basic. */
  static long creditsOf(JsonNode root, String searchDepth) {
    JsonNode credits = root.path("usage").path("credits");
    if (credits.isNumber() && credits.asLong() >= 0) {
      return credits.asLong();
    }
    return "advanced".equals(searchDepth) ? 2 : 1;
  }

  private QuotaCounter.Counter scout() {
    return new QuotaCounter.Counter(keys.global(), QuotaKeys.SCOUT_CREDITS);
  }
}
