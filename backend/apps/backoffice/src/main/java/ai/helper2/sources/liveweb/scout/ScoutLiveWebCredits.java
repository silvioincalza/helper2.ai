package ai.helper2.sources.liveweb.scout;

import ai.helper2.platform.quota.QuotaCounter;
import ai.helper2.platform.quota.QuotaKeys;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

public class ScoutLiveWebCredits {

  private static final Logger log = LoggerFactory.getLogger(ScoutLiveWebCredits.class);

  static final String FEATURE = "scout-discovery";

  private final QuotaCounter counter;
  private final QuotaKeys keys;
  private final long dailyCap;
  private final MeterRegistry meters;
  private final String feature;

  public ScoutLiveWebCredits(
      QuotaCounter counter, QuotaKeys keys, long dailyCap, MeterRegistry meters) {
    this(counter, keys, dailyCap, meters, FEATURE);
  }

  private ScoutLiveWebCredits(
      QuotaCounter counter, QuotaKeys keys, long dailyCap, MeterRegistry meters, String feature) {
    this.counter = counter;
    this.keys = keys;
    this.dailyCap = dailyCap;
    this.meters = meters;
    this.feature = feature;
  }

  public ScoutLiveWebCredits forFeature(String feature) {
    return new ScoutLiveWebCredits(counter, keys, dailyCap, meters, feature);
  }

  public long dailyCap() {
    return dailyCap;
  }

  public boolean capReached() {
    try {
      return counter.used(scout()) >= dailyCap;
    } catch (RuntimeException e) {
      log.warn("Scout live web credits unreadable: {}", e.getClass().getSimpleName());
      return true;
    }
  }

  public JsonNode countedAttempt(String searchDepth, Supplier<JsonNode> post) {
    JsonNode root;
    try {
      root = post.get();
    } catch (RuntimeException e) {
      count(creditsOf(MissingNode.getInstance(), searchDepth));
      throw e;
    }
    spent(root, searchDepth);
    return root;
  }

  public long spent(JsonNode root, String searchDepth) {
    long credits = creditsOf(root, searchDepth);
    count(credits);
    return credits;
  }

  private void count(long credits) {
    meters.counter("helper2.live-web.credits", "feature", feature).increment(credits);
    try {
      counter.add(new QuotaCounter.Counter(keys.global(), QuotaKeys.CREDITS), credits);
      counter.add(scout(), credits);
    } catch (RuntimeException e) {
      log.warn("Scout live web credits not counted: {}", e.getClass().getSimpleName());
    }
  }

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
