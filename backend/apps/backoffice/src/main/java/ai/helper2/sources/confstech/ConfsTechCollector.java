package ai.helper2.sources.confstech;

import ai.helper2.platform.resilience.CallRefused;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.Collector;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.CollectorIds;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.EventFormat;
import ai.helper2.shared.EventKind;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.sources.config.CollectorDescriptors;
import ai.helper2.sources.support.Classification;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public class ConfsTechCollector implements Collector {

  static final String ID = CollectorIds.CONFS_TECH;

  static final String POLICY = "collector-" + ID;

  static final String YEAR_PATH = "/repos/tech-conferences/conference-data/contents/conferences/";
  static final URI RAW_HOST = URI.create("https://raw.githubusercontent.com");

  static final String FOREIGN_DOWNLOAD_URL = "ForeignDownloadUrl";

  private static final Logger log = LoggerFactory.getLogger(ConfsTechCollector.class);

  private final RestClient http;
  private final RestClient raw;
  private final URI rawHost;
  private final Clock clock;
  private final Resilience resilience;
  private final RunInterval interval;
  private final SourceDescriptor descriptor;

  public ConfsTechCollector(
      SourceSettings settings,
      RestClient.Builder builder,
      Clock clock,
      Resilience resilience,
      RunInterval interval) {
    this(settings, builder, clock, resilience, interval, RAW_HOST);
  }

  ConfsTechCollector(
      SourceSettings settings,
      RestClient.Builder builder,
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
    this.raw = builder.clone().build();
    this.http = HttpClients.gitHubClient(builder, settings);
    this.descriptor = CollectorDescriptors.confsTech(settings);
  }

  @Override
  public SourceDescriptor getDescriptor() {
    return descriptor;
  }

  @Override
  public CollectedBatch collect(
      @Nullable String checkpoint, @Nullable String resumeCursor, int maxItems) {
    Instant startedAt = clock.instant();
    if (!interval.isDue(checkpoint, startedAt)) {
      return CollectedBatch.notDue();
    }
    LocalDate today = LocalDate.ofInstant(startedAt, ZoneOffset.UTC);
    Map<String, SourceHit> byUrl = new LinkedHashMap<>();
    int skipped = 0;
    for (int year : List.of(today.getYear(), today.getYear() + 1)) {
      for (URI file : topicFiles(year)) {
        try {
          JsonNode conferences =
              resilience.call(
                  POLICY,
                  () ->
                      HttpClients.getJson(
                          () -> raw.get().uri(file).retrieve().body(String.class), clock));
          for (JsonNode c : conferences) {
            if (byUrl.size() >= maxItems) {
              break;
            }
            toHit(c, today, startedAt, byUrl.size())
                .ifPresent(h -> byUrl.putIfAbsent(h.getCanonicalUrl().getValue(), h));
          }
        } catch (CallRefused e) {
          throw e;
        } catch (RuntimeException e) {
          skipped++;
          HttpClients.dropped(ID, e);
          log.warn("confs-tech: {} skipped: {}", file.getPath(), e.getClass().getSimpleName());
        }
      }
    }
    if (skipped > 0) {
      log.warn("confs-tech: {} of the topic files were skipped in this pass", skipped);
    }
    return new CollectedBatch(List.copyOf(byUrl.values()), null, startedAt.toString());
  }

  private List<URI> topicFiles(int year) {
    JsonNode files;
    try {
      files =
          resilience.call(
              POLICY,
              () ->
                  HttpClients.getJson(
                      () ->
                          http.get()
                              .uri(YEAR_PATH + year)
                              .retrieve()
                              .onStatus(
                                  status -> status.value() == HttpStatus.NOT_FOUND.value(),
                                  (request, response) -> {
                                    throw new YearNotPublished();
                                  })
                              .body(String.class),
                      clock));
    } catch (YearNotPublished e) {
      log.info("confs-tech: no conference folder for {} yet", year);
      return List.of();
    }
    List<URI> out = new ArrayList<>();
    for (JsonNode f : files) {
      String name = HttpClients.text(f, "name");
      String download = HttpClients.text(f, "download_url");
      if (name == null || download == null || !name.endsWith(".json")) {
        continue;
      }
      Optional<URI> url = HttpClients.onHost(rawHost, download);
      if (url.isEmpty()) {
        HttpClients.dropped(ID, FOREIGN_DOWNLOAD_URL);
        log.warn("confs-tech: {} skipped, download URL is not on {}", name, rawHost.getHost());
        continue;
      }
      out.add(url.get());
    }
    return out;
  }

  static final class YearNotPublished extends RuntimeException {
    YearNotPublished() {
      super(null, null, false, false);
    }
  }

  static Optional<SourceHit> toHit(JsonNode c, LocalDate today, Instant now, int position) {
    String name = HttpClients.text(c, "name");
    String link = HttpClients.text(c, "url");
    CanonicalUrl canonical = link == null ? null : CanonicalUrl.of(link);
    LocalDate start = day(HttpClients.text(c, "startDate"));
    if (start == null) {
      return Optional.empty();
    }
    LocalDate end = Objects.requireNonNullElse(day(HttpClients.text(c, "endDate")), start);
    if (name == null
        || canonical == null
        || end.isBefore(start)
        || end.isBefore(today)
        || !Classification.isAiRelated(name, null, Set.of())) {
      return Optional.empty();
    }
    String city = HttpClients.text(c, "city");
    String country = HttpClients.text(c, "country");
    String location =
        Stream.of(city, country).filter(Objects::nonNull).collect(Collectors.joining(", "));
    String year = String.valueOf(start.getYear());
    return Optional.of(
        new SourceHit(
            ID,
            canonical.getValue(),
            HttpClients.link(link, canonical),
            canonical,
            null,
            name.contains(year) ? name : name + " " + year,
            null,
            ValueOrigin.SOURCE_PROVIDED,
            ResourceType.EVENT,
            Audience.BUILD_WITH_AI,
            null,
            Set.of(),
            Set.of("conference"),
            null,
            null,
            Set.of(),
            null,
            DateFact.unknown(),
            DateFact.unknown(),
            List.of(),
            now,
            position,
            DateFact.scheduledDay(start, ID),
            DateFact.scheduledDay(end, ID),
            location.isEmpty() ? null : location,
            EventKind.CONFERENCE,
            format(c.path("online").asBoolean(false), city != null)));
  }

  static @Nullable EventFormat format(boolean online, boolean hasCity) {
    if (online) {
      return hasCity ? EventFormat.HYBRID : EventFormat.ONLINE;
    }
    return hasCity ? EventFormat.IN_PERSON : null;
  }

  static @Nullable LocalDate day(@Nullable String text) {
    if (text == null) {
      return null;
    }
    try {
      return LocalDate.parse(text.strip());
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
