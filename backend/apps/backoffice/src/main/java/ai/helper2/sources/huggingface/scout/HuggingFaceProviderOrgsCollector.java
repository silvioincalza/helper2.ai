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
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public class HuggingFaceProviderOrgsCollector implements Collector {

  private static final Logger log = LoggerFactory.getLogger(HuggingFaceProviderOrgsCollector.class);

  static final String ID = CollectorIds.HF_PROVIDER_ORGS;

  static final String POLICY = "collector-" + ID;

  private final RestClient http;
  private final Clock clock;
  private final Resilience resilience;
  private final RunInterval interval;
  private final List<String> orgs;
  private final int perOrg;
  private final SourceDescriptor descriptor;

  public HuggingFaceProviderOrgsCollector(
      SourceSettings settings,
      RestClient.Builder builder,
      Clock clock,
      Resilience resilience,
      RunInterval interval,
      List<String> orgs,
      int perOrg) {
    this.clock = clock;
    this.resilience = resilience;
    if (settings.enabled()) {
      resilience.require(POLICY);
    }
    this.interval = interval;
    this.orgs = List.copyOf(orgs);
    this.perOrg = Math.max(1, Math.min(perOrg, 100));
    this.http = HttpClients.client(builder, settings);
    this.descriptor = CollectorDescriptors.hfProviderOrgs(settings, orgs);
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
    List<SourceHit> hits = new ArrayList<>();
    RuntimeException lastFailure = null;
    int failed = 0;
    int rank = 0;
    for (String org : orgs) {
      if (hits.size() >= maxItems) {
        break;
      }
      try {
        for (JsonNode m : latestModelsOf(org)) {
          SourceHit hit =
              HuggingFaceModelsSource.toHit(
                  m, ID, startedAt, rank++, Set.of(org.toLowerCase(Locale.ROOT)));
          if (hit != null && hits.size() < maxItems) {
            hits.add(hit);
          }
        }
      } catch (RuntimeException e) {
        log.warn("{}: the models of an organisation failed: {}", ID, e.getClass().getSimpleName());
        lastFailure = e;
        failed++;
      }
    }
    if (lastFailure != null && failed == orgs.size()) {
      throw lastFailure;
    }
    return new CollectedBatch(hits, null, failed == 0 ? startedAt.toString() : null);
  }

  private JsonNode latestModelsOf(String org) {
    return resilience.call(
        POLICY,
        () ->
            HttpClients.getJson(
                () ->
                    http.get()
                        .uri(u ->
                            u.path("/api/models")
                                .queryParam("author", org)
                                .queryParam("sort", "createdAt")
                                .queryParam("direction", -1)
                                .queryParam("limit", perOrg)
                                .build())
                        .retrieve()
                        .body(String.class),
                clock));
  }
}
