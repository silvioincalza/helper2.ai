package ai.searchavo.sources.arxiv.scout;

import ai.searchavo.platform.http.ObservedHttp;
import ai.searchavo.platform.resilience.OutboundThrottle;
import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.search.SourceFailure;
import ai.searchavo.search.SourceHit;
import ai.searchavo.sources.arxiv.ArxivSource;
import ai.searchavo.sources.support.HttpClients;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.web.client.RestClient;

public final class ArxivPaperReader {

  public static final int BATCH = 50;

  static final int MAX_BYTES = 2 * 1024 * 1024;

  static final int SLOT_TRIES = 5;

  static final Duration SLOT_WAIT = Duration.ofMinutes(1);

  private static final Pattern ID =
      Pattern.compile("\\d{4}\\.\\d{4,5}|[a-z][a-z-]{1,20}(\\.[A-Z]{2})?/\\d{7}");

  private final RestClient http;
  private final Resilience resilience;
  private final String policy;
  private final Clock clock;
  private final OutboundThrottle throttle;
  private final Consumer<Duration> pause;

  public ArxivPaperReader(
      RestClient http,
      Resilience resilience,
      String policy,
      Clock clock,
      OutboundThrottle throttle,
      Consumer<Duration> pause) {
    this.http = http;
    this.resilience = resilience;
    this.policy = policy;
    this.clock = clock;
    this.throttle = throttle;
    this.pause = pause;
  }

  public static Consumer<Duration> sleeping() {
    return duration -> {
      try {
        Thread.sleep(duration);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };
  }

  public List<SourceHit> read(List<String> ids, String sourceId, Instant now) {
    List<String> asked =
        ids.stream().filter(id -> ID.matcher(id).matches()).distinct().limit(BATCH).toList();
    if (asked.isEmpty()) {
      return List.of();
    }
    String feed =
        resilience.call(
            policy,
            () -> {
              waitForTheSharedSlot();
              return HttpClients.getText(
                  () ->
                      http.get()
                          .uri(u ->
                              u.path("/api/query")
                                  .queryParam("id_list", String.join(",", asked))
                                  .queryParam("max_results", asked.size())
                                  .build())
                          .attributes(ObservedHttp.maxResponseBytes(MAX_BYTES))
                          .retrieve()
                          .body(String.class),
                  clock);
            });
    return ArxivSource.papers(feed, sourceId, now).stream().filter(ArxivSource::isAboutAi).toList();
  }

  private void waitForTheSharedSlot() {
    for (int tried = 0; tried < SLOT_TRIES; tried++) {
      if (ArxivSource.takesSlot(throttle)) {
        return;
      }
      pause.accept(ArxivSource.MIN_INTERVAL);
    }
    throw new SourceFailure.RateLimited(SLOT_WAIT);
  }
}
