package ai.helper2.sources.huggingface.scout;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.Collector;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.CollectorIds;
import ai.helper2.sources.config.CollectorDescriptors;
import ai.helper2.sources.huggingface.HuggingFaceModelsSource;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public class HuggingFaceTrendingCollector implements Collector {

  static final String ID = CollectorIds.HF_TRENDING;

  static final String POLICY = "collector-" + ID;

  private static final int MAX_PAGE = 100;

  private final RestClient http;
  private final Clock clock;
  private final Resilience resilience;
  private final RunInterval interval;
  private final SourceDescriptor descriptor;

  public HuggingFaceTrendingCollector(
      SourceSettings settings,
      RestClient.Builder builder,
      Clock clock,
      Resilience resilience,
      RunInterval interval) {
    this.clock = clock;
    this.resilience = resilience;
    if (settings.enabled()) {
      resilience.require(POLICY);
    }
    this.interval = interval;
    this.http = HttpClients.client(builder, settings);
    this.descriptor = CollectorDescriptors.hfTrending(settings);
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
    int limit = Math.max(1, Math.min(maxItems, MAX_PAGE));
    JsonNode root =
        resilience.call(
            POLICY,
            () ->
                HttpClients.getJson(
                    () ->
                        http.get()
                            .uri(u ->
                                u.path("/api/models")
                                    .queryParam("sort", "trendingScore")
                                    .queryParam("direction", -1)
                                    .queryParam("limit", limit)
                                    .build())
                            .retrieve()
                            .body(String.class),
                    clock));
    List<SourceHit> hits = new ArrayList<>();
    int rank = 0;
    for (JsonNode m : root) {
      SourceHit hit = HuggingFaceModelsSource.toHit(m, ID, startedAt, rank++);
      if (hit != null) {
        hits.add(hit);
      }
    }
    return new CollectedBatch(hits, null, startedAt.toString());
  }
}
