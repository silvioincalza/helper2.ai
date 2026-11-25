package ai.searchavo.sources.aideadlines;

import ai.searchavo.platform.resilience.CallRefused;
import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.scout.CollectedBatch;
import ai.searchavo.scout.Collector;
import ai.searchavo.search.SourceDescriptor;
import ai.searchavo.search.SourceHit;
import ai.searchavo.shared.Audience;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.CollectorIds;
import ai.searchavo.shared.DateFact;
import ai.searchavo.shared.EventKind;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.sources.config.CollectorDescriptors;
import ai.searchavo.sources.support.HttpClients;
import ai.searchavo.sources.support.RunInterval;
import ai.searchavo.sources.support.SourceSettings;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;

public class AiDeadlinesCollector implements Collector {

  static final String ID = CollectorIds.AI_DEADLINES;

  static final String POLICY = "collector-" + ID;

  static final String CONFERENCES_PATH =
      "/repos/huggingface/ai-deadlines/contents/src/data/conferences";
  static final URI RAW_HOST = URI.create("https://raw.githubusercontent.com");

  static final String FOREIGN_DOWNLOAD_URL = "ForeignDownloadUrl";

  private static final Logger log = LoggerFactory.getLogger(AiDeadlinesCollector.class);

  private final RestClient http;
  private final RestClient raw;
  private final URI rawHost;
  private final Clock clock;
  private final Resilience resilience;
  private final RunInterval interval;
  private final SourceDescriptor descriptor;

  public AiDeadlinesCollector(
      SourceSettings settings,
      RestClient.Builder builder,
      RestClient.Builder rawWithoutRedirects,
      Clock clock,
      Resilience resilience,
      RunInterval interval) {
    this(settings, builder, rawWithoutRedirects, clock, resilience, interval, RAW_HOST);
  }

  AiDeadlinesCollector(
      SourceSettings settings,
      RestClient.Builder builder,
      RestClient.Builder rawWithoutRedirects,
      Clock clock,
      Resilience resilience,
      RunInterval interval,
      URI rawHost) {
    this.clock = clock;
    this.resilience = resilience;
    if (settings.enabled()) {
      resilience.require(POLICY);
    }
    this.interval = interval;
    this.rawHost = rawHost;
    this.raw = rawWithoutRedirects.build();
    this.http = HttpClients.gitHubClient(builder, settings);
    this.descriptor = CollectorDescriptors.aiDeadlines(settings);
  }

  @Override
  public SourceDescriptor getDescriptor() {
    return descriptor;
  }

  @Override
  public Duration runEvery() {
    return interval.every();
  }

  @Override
  public CollectedBatch collect(String checkpoint, String resumeCursor, int maxItems) {
    Instant startedAt = clock.instant();
    if (!interval.isDue(checkpoint, startedAt)) {
      return CollectedBatch.notDue();
    }
    LocalDate today = LocalDate.ofInstant(startedAt, ZoneOffset.UTC);
    JsonNode files =
        resilience.call(
            POLICY,
            () ->
                HttpClients.getJson(
                    () -> http.get().uri(CONFERENCES_PATH).retrieve().body(String.class), clock));
    List<SourceHit> hits = new ArrayList<>();
    int rank = 0;
    int skipped = 0;
    for (JsonNode f : files) {
      String name = HttpClients.text(f, "name");
      String download = HttpClients.text(f, "download_url");
      if (name == null || download == null || !name.endsWith(".yml") || hits.size() >= maxItems) {
        continue;
      }
      Optional<URI> url = allowed(download);
      if (url.isEmpty()) {
        skipped++;
        HttpClients.dropped(ID, FOREIGN_DOWNLOAD_URL);
        log.warn("ai-deadlines: {} skipped, download URL is not on {}", name, rawHost.getHost());
        continue;
      }
      try {
        String yaml =
            resilience.call(POLICY, () -> HttpClients.getWithoutRedirect(raw, url.get(), clock));
        Optional<SourceHit> hit =
            nextEdition(name.substring(0, name.length() - 4), yaml, today, startedAt, rank);
        if (hit.isPresent()) {
          hits.add(hit.get());
          rank++;
        }
      } catch (CallRefused e) {
        throw e;
      } catch (RuntimeException e) {
        skipped++;
        HttpClients.dropped(ID, e);
        log.warn("ai-deadlines: {} skipped: {}", name, e.getClass().getSimpleName());
      }
    }
    if (skipped > 0) {
      log.warn("ai-deadlines: {} of the conference files were skipped in this pass", skipped);
    }
    return new CollectedBatch(hits, null, startedAt.toString());
  }

  Optional<URI> allowed(String download) {
    return HttpClients.onHost(rawHost, download);
  }

  static Optional<SourceHit> nextEdition(
      String conference, String yaml, LocalDate today, Instant now, int position) {
    Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
    if (!(parsed instanceof List<?> editions)) {
      return Optional.empty();
    }
    return editions.stream()
        .filter(Map.class::isInstance)
        .map(e -> (Map<?, ?>) e)
        .map(e -> new Edition(e, day(e.get("start")), day(e.get("end"))))
        .filter(e -> e.start != null && !Objects.requireNonNullElse(e.end, e.start).isBefore(today))
        .min(Comparator.comparing(Edition::start))
        .flatMap(e -> toHit(conference, e, now, position));
  }

  private record Edition(Map<?, ?> raw, LocalDate start, LocalDate end) {
    String text(String key) {
      Object v = raw.get(key);
      return v == null || v.toString().isBlank() ? null : v.toString().strip();
    }
  }

  private static Optional<SourceHit> toHit(
      String conference, Edition e, Instant now, int position) {
    String link = e.text("link");
    CanonicalUrl canonical = link == null ? null : CanonicalUrl.of(link);
    String title = e.text("title");
    if (canonical == null || title == null) {
      return Optional.empty();
    }
    String year = e.text("year");
    String location =
        Stream.of(e.text("city"), e.text("country"))
            .filter(Objects::nonNull)
            .collect(Collectors.joining(", "));
    String description =
        Stream.of(e.text("full_name"), e.text("venue"))
            .filter(Objects::nonNull)
            .collect(Collectors.joining(". "));
    return Optional.of(
        new SourceHit(
                ID,
                conference,
                HttpClients.link(link, canonical),
                canonical,
                year == null || title.contains(year) ? title : title + " " + year,
                ResourceType.EVENT,
                now)
            .withDescription(description.isEmpty() ? null : description)
            .withAudience(Audience.BUILD_WITH_AI)
            .withTags(Set.of("conference"))
            .withRank(position)
            .withEventDays(DateFact.scheduledDay(e.start(), ID), DateFact.scheduledDay(e.end(), ID))
            .withLocation(location.isEmpty() ? null : location)
            .withEventKind(EventKind.CONFERENCE));
  }

  static LocalDate day(Object v) {
    if (v instanceof Date d) {
      return LocalDate.ofInstant(d.toInstant(), ZoneOffset.UTC);
    }
    if (v instanceof String s && !s.isBlank()) {
      try {
        return LocalDate.parse(s.strip().substring(0, Math.min(10, s.strip().length())));
      } catch (DateTimeParseException ex) {
        return null;
      }
    }
    return null;
  }
}
