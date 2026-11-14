package ai.helper2.sources.liveweb.scout;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.Collector;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.CollectorIds;
import ai.helper2.sources.config.CollectorDescriptors;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.sources.tavily.TavilySource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public class LiveWebNewsCollector implements Collector {

  static final String ID = CollectorIds.LIVE_WEB_SCOUT;

  static final String POLICY = "collector-" + ID;

  static final String FEATURE = "scout-news";
  private static final String SEARCH_DEPTH = "basic";
  private static final int MAX_RESULTS_PER_QUERY = 20;

  private final RestClient http;
  private final Clock clock;
  private final Resilience resilience;
  private final RunInterval interval;
  private final List<String> queries;
  private final SourceDescriptor descriptor;
  private final ScoutLiveWebCredits credits;

  public LiveWebNewsCollector(
      SourceSettings settings,
      RestClient.Builder builder,
      Clock clock,
      Resilience resilience,
      RunInterval interval,
      List<String> queries,
      ScoutLiveWebCredits credits) {
    this.clock = clock;
    this.credits = credits.forFeature(FEATURE);
    this.resilience = resilience;
    if (settings.enabled()) {
      resilience.require(POLICY);
    }
    this.interval = interval;
    this.queries = List.copyOf(queries);
    this.http = HttpClients.client(builder, settings);
    this.descriptor = CollectorDescriptors.liveWebScout(settings, this.queries);
  }

  @Override
  public SourceDescriptor getDescriptor() {
    return descriptor;
  }

  @Override
  public CollectedBatch collect(String checkpoint, String resumeCursor, int maxItems) {
    Instant startedAt = clock.instant();
    if (!interval.allowsPass(checkpoint, resumeCursor, startedAt)) {
      return CollectedBatch.notDue();
    }
    if (queries.isEmpty()) {
      return new CollectedBatch(List.of(), null, startedAt.toString());
    }
    if (credits.capReached()) {
      return CollectedBatch.deferred(
          "Daily live web credit cap of the Scout reached ("
              + credits.dailyCap()
              + " credits); next try tomorrow");
    }
    Map<String, SourceHit> byUrl = new LinkedHashMap<>();
    int perQuery = Math.max(1, Math.min(MAX_RESULTS_PER_QUERY, maxItems / queries.size()));
    String since = startDate(checkpoint);
    Set<String> done = QueryProgress.done(resumeCursor);
    for (String query : queries) {
      if (done.contains(query)) {
        continue;
      }
      if (credits.capReached()) {
        break;
      }
      JsonNode root = search(requestBody(query, since, perQuery));
      for (JsonNode r : root.path("results")) {
        SourceHit hit = TavilySource.toHit(r, ID, startedAt, byUrl.size());
        if (hit != null) {
          byUrl.putIfAbsent(hit.getExternalId(), hit);
        }
      }
      done.add(query);
    }
    boolean complete = done.containsAll(queries);
    return new CollectedBatch(
        List.copyOf(byUrl.values()),
        complete ? null : QueryProgress.cursor(done),
        complete ? startedAt.toString() : null);
  }

  private static Map<String, Object> requestBody(String query, String since, int maxResults) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("query", query);
    body.put("topic", "news");
    body.put("search_depth", SEARCH_DEPTH);
    body.put("max_results", maxResults);
    if (since != null) {
      body.put("start_date", since);
    } else {
      body.put("time_range", "week");
    }
    return body;
  }

  private JsonNode search(Map<String, Object> body) {
    return resilience.call(
        POLICY,
        () ->
            credits.countedAttempt(
                SEARCH_DEPTH,
                () ->
                    HttpClients.getJson(
                        () -> http.post().uri("/search").body(body).retrieve().body(String.class),
                        clock)));
  }

  static String startDate(String checkpoint) {
    if (checkpoint == null) {
      return null;
    }
    try {
      return Instant.parse(checkpoint).atZone(ZoneOffset.UTC).toLocalDate().toString();
    } catch (java.time.format.DateTimeParseException e) {
      return null;
    }
  }
}
