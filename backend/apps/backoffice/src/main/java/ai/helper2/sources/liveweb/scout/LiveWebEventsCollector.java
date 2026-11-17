package ai.helper2.sources.liveweb.scout;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.Collector;
import ai.helper2.scout.LlmUnavailable;
import ai.helper2.scout.PageScope;
import ai.helper2.scout.UntrustedPageText;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.shared.WebSources;
import ai.helper2.sources.config.CollectorDescriptors;
import ai.helper2.sources.events.EventQuoteGate;
import ai.helper2.sources.events.EventReader;
import ai.helper2.sources.events.EventReading;
import ai.helper2.sources.events.UndatedEvents;
import ai.helper2.sources.evidence.LiveWebPageReader;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.sources.tavily.TavilySource;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public class LiveWebEventsCollector implements Collector {

  public static final String ID = WebSources.EVENTS_SCOUT;

  static final String POLICY = "collector-" + ID;

  static final String SEARCH_DEPTH = "basic";
  static final int MAX_RESULTS_PER_QUERY = 10;
  static final int EXTRACT_BATCH = 4;
  private static final int MAX_EXTRACT_BYTES = 2 * 1024 * 1024;

  static final String UNDATED = "catalog:undated-events";
  static final String UNDATED_AFTER = UNDATED + "@";
  static final Duration UNDATED_LOOKBACK = Duration.ofDays(30);

  private static final Logger log = LoggerFactory.getLogger(LiveWebEventsCollector.class);

  private final RestClient http;
  private final Clock clock;
  private final Resilience resilience;
  private final RunInterval interval;
  private final List<String> queries;
  private final int maxPages;
  private final ScoutLiveWebCredits credits;
  private final @Nullable EventReader reader;
  private final UndatedEvents undated;
  private final EventQuoteGate gate = new EventQuoteGate();
  private final SourceDescriptor descriptor;

  public LiveWebEventsCollector(
      SourceSettings settings,
      RestClient.Builder builder,
      Clock clock,
      Resilience resilience,
      RunInterval interval,
      List<String> queries,
      int maxPages,
      ScoutLiveWebCredits credits,
      @Nullable EventReader reader,
      UndatedEvents undated) {
    this.clock = clock;
    this.undated = undated;
    this.resilience = resilience;
    this.interval = interval;
    this.queries = List.copyOf(queries);
    this.maxPages = maxPages;
    this.credits = credits.forFeature("scout-events");
    this.reader = reader;
    boolean enabled = CollectorDescriptors.liveWebEventsOn(settings, this.queries, reader != null);
    if (enabled) {
      resilience.require(POLICY);
    }
    this.http = HttpClients.client(builder, settings);
    this.descriptor = CollectorDescriptors.liveWebEvents(settings, this.queries, reader != null);
  }

  @Override
  public SourceDescriptor getDescriptor() {
    return descriptor;
  }

  @Override
  public CollectedBatch collect(
      @Nullable String checkpoint, @Nullable String resumeCursor, int maxItems) {
    Instant startedAt = clock.instant();
    if (!interval.allowsPass(checkpoint, resumeCursor, startedAt)) {
      return CollectedBatch.skipped(notDueReason(checkpoint, startedAt));
    }
    if (reader == null) {
      return CollectedBatch.skipped("No AI model configured to read event pages");
    }
    if (credits.capReached()) {
      return CollectedBatch.deferred(
          "Daily live web credit cap of the Scout reached ("
              + credits.dailyCap()
              + " credits); next try tomorrow");
    }
    Set<String> done = QueryProgress.done(resumeCursor);
    int limit = Math.min(maxPages, Math.max(1, maxItems));
    UndatedBatch catalog =
        undatedBatch(undatedSince(checkpoint, startedAt), startedAt, limit, done);
    Found found = pagesToRead(startedAt, limit, done);
    List<SourceHit> pages = onePerUrl(catalog.hits(), found.pages());
    Reading reading = readAll(pages, startedAt);
    log.info(
        "{}: {} search and {} undated catalog pages, {} events kept",
        ID,
        found.pages().size(),
        catalog.hits().size(),
        reading.events().size());
    markSearchesDone(found, pages, reading.processed(), done);
    markCatalogProgress(catalog, reading.processed(), done);
    boolean complete = done.containsAll(queries) && done.contains(UNDATED);
    return new CollectedBatch(
        reading.events(),
        complete ? null : QueryProgress.cursor(done),
        complete ? startedAt.toString() : null);
  }

  private record Found(List<SourceHit> pages, Map<String, Set<String>> urlsByQuery) {}

  private record Reading(List<SourceHit> events, Set<String> processed) {}

  private record UndatedBatch(List<UndatedEvents.Page> pages, List<SourceHit> hits, boolean full) {
    static final UndatedBatch NONE = new UndatedBatch(List.of(), List.of(), false);
  }

  static List<SourceHit> onePerUrl(List<SourceHit> first, List<SourceHit> then) {
    Map<String, SourceHit> byUrl = new LinkedHashMap<>();
    first.forEach(h -> byUrl.putIfAbsent(h.getCanonicalUrl().getValue(), h));
    then.forEach(h -> byUrl.putIfAbsent(h.getCanonicalUrl().getValue(), h));
    return List.copyOf(byUrl.values());
  }

  private static void markSearchesDone(
      Found found, List<SourceHit> read, Set<String> processed, Set<String> done) {
    Set<String> selected = new LinkedHashSet<>();
    read.forEach(h -> selected.add(h.getCanonicalUrl().getValue()));
    found
        .urlsByQuery()
        .forEach((query, urls) -> {
          boolean allRead = urls.stream().filter(selected::contains).allMatch(processed::contains);
          if (allRead) {
            done.add(query);
          }
        });
  }

  private static void markCatalogProgress(
      UndatedBatch batch, Set<String> processed, Set<String> done) {
    if (done.contains(UNDATED)) {
      return;
    }
    UndatedEvents.Page lastRead = null;
    for (UndatedEvents.Page page : batch.pages()) {
      CanonicalUrl canonical = CanonicalUrl.of(page.url());
      if (canonical != null && !processed.contains(canonical.getValue()) && readable(page)) {
        break;
      }
      lastRead = page;
    }
    boolean allRead =
        lastRead == null ? batch.pages().isEmpty() : lastRead == batch.pages().getLast();
    if (allRead && !batch.full()) {
      done.removeIf(key -> key.startsWith(UNDATED_AFTER));
      done.add(UNDATED);
    } else if (lastRead != null) {
      done.removeIf(key -> key.startsWith(UNDATED_AFTER));
      done.add(UNDATED_AFTER + lastRead.position().token());
    }
  }

  private static boolean readable(UndatedEvents.Page page) {
    try {
      return CanonicalUrl.of(page.url()) != null
          && PageScope.plainUrlProblem(URI.create(page.url())) == null;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private String notDueReason(@Nullable String checkpoint, Instant now) {
    LocalDate next = interval.nextDueDay(checkpoint, now);
    return next == null ? "Not due yet (run interval)" : "Not due yet: next pass on " + next;
  }

  static Instant undatedSince(@Nullable String checkpoint, Instant now) {
    if (checkpoint != null) {
      try {
        return Instant.parse(checkpoint);
      } catch (DateTimeParseException e) {
        log.debug("{}: checkpoint {} is not an instant", ID, checkpoint);
      }
    }
    return now.minus(UNDATED_LOOKBACK);
  }

  private UndatedBatch undatedBatch(Instant since, Instant now, int limit, Set<String> done) {
    if (done.contains(UNDATED)) {
      return UndatedBatch.NONE;
    }
    List<UndatedEvents.Page> batch = undated.discoveredSince(since, undatedPosition(done), limit);
    return new UndatedBatch(batch, asPages(batch, now), batch.size() >= limit);
  }

  private static UndatedEvents.@Nullable Position undatedPosition(Set<String> done) {
    return done.stream()
        .filter(key -> key.startsWith(UNDATED_AFTER))
        .findFirst()
        .flatMap(key -> UndatedEvents.Position.parse(key.substring(UNDATED_AFTER.length())))
        .orElse(null);
  }

  private static List<SourceHit> asPages(List<UndatedEvents.Page> batch, Instant now) {
    List<SourceHit> pages = new ArrayList<>();
    for (UndatedEvents.Page p : batch) {
      CanonicalUrl canonical = CanonicalUrl.of(p.url());
      URI url;
      try {
        url = URI.create(p.url());
      } catch (IllegalArgumentException e) {
        continue;
      }
      if (canonical == null || PageScope.plainUrlProblem(url) != null) {
        continue;
      }
      pages.add(
          new SourceHit(
              ID,
              canonical.getValue(),
              url,
              canonical,
              null,
              p.title(),
              null,
              ValueOrigin.SOURCE_PROVIDED,
              ResourceType.EVENT,
              Audience.BOTH,
              null,
              Set.of(),
              Set.of(),
              null,
              null,
              Set.of(),
              null,
              DateFact.unknown(),
              DateFact.unknown(),
              List.of(),
              now,
              pages.size(),
              DateFact.unknown(),
              DateFact.unknown(),
              null,
              null,
              null,
              Map.of("query", UNDATED)));
    }
    return pages;
  }

  private Found pagesToRead(Instant now, int limit, Set<String> done) {
    List<List<SourceHit>> perQuery = new ArrayList<>();
    Map<String, Set<String>> urlsByQuery = new LinkedHashMap<>();
    RuntimeException lastFailure = null;
    int failed = 0;
    for (String query : queries) {
      if (done.contains(query)) {
        continue;
      }
      if (credits.capReached()) {
        log.info("{}: credit cap reached, the pass stays open", ID);
        break;
      }
      try {
        JsonNode root = post("/search", searchBody(query));
        List<SourceHit> found = new ArrayList<>();
        for (JsonNode r : root.path("results")) {
          SourceHit hit =
              TavilySource.toHit(r, ID, now, found.size(), ResourceType.EVENT, Set.of());
          if (hit != null && PageScope.plainUrlProblem(hit.getUrl()) == null) {
            found.add(hit.withFacts(Map.of("query", query)));
          }
        }
        perQuery.add(found);
        Set<String> urls = new LinkedHashSet<>();
        found.forEach(h -> urls.add(h.getCanonicalUrl().getValue()));
        urlsByQuery.put(query, urls);
      } catch (RuntimeException e) {
        log.warn("{}: a search failed: {}", ID, e.getClass().getSimpleName());
        lastFailure = e;
        failed++;
      }
    }
    if (lastFailure != null && failed * 2 >= queries.size()) {
      throw lastFailure;
    }
    return new Found(interleaved(perQuery, limit), urlsByQuery);
  }

  static List<SourceHit> interleaved(List<List<SourceHit>> perQuery, int limit) {
    Map<String, SourceHit> byUrl = new LinkedHashMap<>();
    int longest = perQuery.stream().mapToInt(List::size).max().orElse(0);
    for (int rank = 0; rank < longest && byUrl.size() < limit; rank++) {
      for (List<SourceHit> found : perQuery) {
        if (rank < found.size() && byUrl.size() < limit) {
          SourceHit hit = found.get(rank);
          byUrl.putIfAbsent(hit.getCanonicalUrl().getValue(), hit);
        }
      }
    }
    return List.copyOf(byUrl.values());
  }

  private Reading readAll(List<SourceHit> pages, Instant now) {
    List<SourceHit> eventSites = new ArrayList<>();
    Reading reading = readPages(pages, now, eventSites);
    if (eventSites.isEmpty() || reading.processed().size() < pages.size()) {
      return reading;
    }
    List<SourceHit> events = new ArrayList<>(reading.events());
    events.addAll(readPages(onePerUrl(eventSites, List.of()), now, new ArrayList<>()).events());
    return new Reading(events, reading.processed());
  }

  private Reading readPages(List<SourceHit> pages, Instant now, List<SourceHit> eventSites) {
    List<SourceHit> events = new ArrayList<>();
    Set<String> processed = new LinkedHashSet<>();
    LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
    for (int i = 0; i < pages.size(); i += EXTRACT_BATCH) {
      if (credits.capReached()) {
        log.info("{}: credit cap reached, {} pages left unread", ID, pages.size() - i);
        return new Reading(events, processed);
      }
      List<SourceHit> batch = pages.subList(i, Math.min(pages.size(), i + EXTRACT_BATCH));
      Map<String, SourceHit> byUrl = new LinkedHashMap<>();
      batch.forEach(h -> byUrl.put(h.getCanonicalUrl().getValue(), h));
      JsonNode root;
      try {
        root = post("/extract", extractBody(batch));
      } catch (RuntimeException e) {
        log.warn(
            "{}: a page read failed, {} pages left unread: {}",
            ID,
            pages.size() - i,
            e.getClass().getSimpleName());
        return new Reading(events, processed);
      }
      try {
        for (JsonNode r : root.path("results")) {
          Optional<SourceHit> event = readPage(r, byUrl, today);
          event.ifPresent(events::add);
          if (event.isEmpty()) {
            eventSiteOfPost(r, byUrl, now).ifPresent(eventSites::add);
          }
          CanonicalUrl url = CanonicalUrl.of(HttpClients.text(r, "url"));
          if (url != null) {
            processed.add(url.getValue());
          }
        }
      } catch (LlmUnavailable e) {
        log.info("{}: no model to read pages now ({})", ID, e.getMessage());
        return new Reading(events, processed);
      }
      for (String url : byUrl.keySet()) {
        if (processed.add(url)) {
          log.debug("{}: {} could not be extracted, it is not read again", ID, url);
        }
      }
    }
    return new Reading(events, processed);
  }

  private static Optional<SourceHit> eventSiteOfPost(
      JsonNode r, Map<String, SourceHit> byUrl, Instant now) {
    CanonicalUrl url = CanonicalUrl.of(HttpClients.text(r, "url"));
    SourceHit post = url == null ? null : byUrl.get(url.getValue());
    String raw = HttpClients.text(r, "raw_content");
    if (post == null || raw == null || !LinkedInPosts.isPost(post.getCanonicalUrl())) {
      return Optional.empty();
    }
    return LinkedInPosts.eventSite(raw)
        .map(site -> {
          Map<String, Object> facts = new LinkedHashMap<>(post.getFacts());
          facts.put("linkedFrom", post.getCanonicalUrl().getValue());
          return new SourceHit(
              ID,
              CanonicalUrl.of(site.toString()).getValue(),
              site,
              CanonicalUrl.of(site.toString()),
              null,
              LinkedInPosts.eventTitle(post.getTitle()),
              null,
              ValueOrigin.SOURCE_PROVIDED,
              ResourceType.EVENT,
              Audience.BOTH,
              null,
              Set.of(),
              Set.of(),
              null,
              null,
              Set.of(),
              null,
              DateFact.unknown(),
              DateFact.unknown(),
              List.of(),
              now,
              0,
              DateFact.unknown(),
              DateFact.unknown(),
              null,
              null,
              null,
              facts);
        });
  }

  private Optional<SourceHit> readPage(JsonNode r, Map<String, SourceHit> byUrl, LocalDate today) {
    String url = HttpClients.text(r, "url");
    String raw = HttpClients.text(r, "raw_content");
    String text = raw == null ? null : UntrustedPageText.INSTANCE.clean(raw);
    CanonicalUrl canonical = url == null ? null : CanonicalUrl.of(url);
    SourceHit found = canonical == null ? null : byUrl.get(canonical.getValue());
    if (found == null || text == null || text.isBlank()) {
      return Optional.empty();
    }
    Optional<EventReading> reading = reader.read(found.getTitle(), text);
    if (reading.isEmpty()) {
      return Optional.empty();
    }
    EventQuoteGate.Verdict verdict = gate.check(text, reading.get(), today);
    if (verdict.event().isEmpty()) {
      log.debug("{}: {} not kept: {}", ID, found.getCanonicalUrl().getHost(), verdict.reason());
      return Optional.empty();
    }
    return Optional.of(eventHit(found, verdict.event().get()));
  }

  private SourceHit eventHit(SourceHit page, EventQuoteGate.CheckedEvent e) {
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("dateQuote", e.dateQuote());
    evidence.put("model", reader.model());
    evidence.put("promptVersion", reader.promptVersion());
    Map<String, Object> facts = new LinkedHashMap<>(page.getFacts());
    facts.put("eventEvidence", evidence);
    return new SourceHit(
        ID,
        page.getExternalId(),
        page.getUrl(),
        page.getCanonicalUrl(),
        null,
        page.getTitle(),
        page.getDescription(),
        page.getDescriptionOrigin(),
        ResourceType.EVENT,
        page.getAudience(),
        null,
        Set.of(),
        Set.of(),
        null,
        null,
        Set.of(),
        null,
        page.getPublished(),
        page.getUpdated(),
        List.of(),
        page.getRetrievedAt(),
        page.getRank(),
        DateFact.scheduledDay(e.start(), ID),
        DateFact.scheduledDay(e.end(), ID),
        e.location(),
        e.kind(),
        e.format(),
        facts);
  }

  static Map<String, Object> searchBody(String query) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("query", query);
    body.put("topic", "general");
    body.put("search_depth", SEARCH_DEPTH);
    body.put("max_results", MAX_RESULTS_PER_QUERY);
    body.put("include_usage", true);
    return body;
  }

  static Map<String, Object> extractBody(List<SourceHit> pages) {
    Map<String, Object> body =
        new LinkedHashMap<>(
            LiveWebPageReader.requestBody(pages.stream().map(SourceHit::getUrl).toList()));
    body.put("include_usage", true);
    return body;
  }

  private JsonNode post(String path, Map<String, Object> body) {
    return resilience.call(
        POLICY,
        () ->
            credits.countedAttempt(
                SEARCH_DEPTH,
                () ->
                    HttpClients.getJson(
                        () ->
                            http.post()
                                .uri(path)
                                .attributes(ObservedHttp.maxResponseBytes(MAX_EXTRACT_BYTES))
                                .body(body)
                                .retrieve()
                                .body(String.class),
                        clock)));
  }
}
