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
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.WebSources;
import ai.helper2.sources.config.CollectorDescriptors;
import ai.helper2.sources.events.EventQuoteGate;
import ai.helper2.sources.events.EventReader;
import ai.helper2.sources.events.EventReading;
import ai.helper2.sources.evidence.LiveWebPageReader;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.sources.tavily.TavilySource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

  private static final Logger log = LoggerFactory.getLogger(LiveWebEventsCollector.class);

  private final RestClient http;
  private final Clock clock;
  private final Resilience resilience;
  private final RunInterval interval;
  private final List<String> queries;
  private final int maxPages;
  private final ScoutLiveWebCredits credits;
  private final @Nullable EventReader reader;
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
      @Nullable EventReader reader) {
    this.clock = clock;
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
    Set<String> doneBefore = QueryProgress.done(resumeCursor);
    Found found =
        pagesToRead(
            startedAt, Math.min(maxPages, Math.max(1, maxItems)), new LinkedHashSet<>(doneBefore));
    Reading reading = readAll(found.pages(), startedAt);
    log.info(
        "{}: {} pages found, {} events kept", ID, found.pages().size(), reading.events().size());
    Set<String> done = reading.pagesLeftUnread() ? doneBefore : found.done();
    boolean complete = done.containsAll(queries);
    return new CollectedBatch(
        reading.events(),
        complete ? null : QueryProgress.cursor(done),
        complete ? startedAt.toString() : null);
  }

  private record Found(List<SourceHit> pages, Set<String> done) {}

  private record Reading(List<SourceHit> events, boolean pagesLeftUnread) {}

  private String notDueReason(@Nullable String checkpoint, Instant now) {
    LocalDate next = interval.nextDueDay(checkpoint, now);
    return next == null ? "Not due yet (run interval)" : "Not due yet: next pass on " + next;
  }

  private Found pagesToRead(Instant now, int limit, Set<String> done) {
    List<List<SourceHit>> perQuery = new ArrayList<>();
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
        done.add(query);
      } catch (RuntimeException e) {
        log.warn("{}: a search failed: {}", ID, e.getClass().getSimpleName());
        lastFailure = e;
        failed++;
      }
    }
    if (lastFailure != null && failed * 2 >= queries.size()) {
      throw lastFailure;
    }
    return new Found(interleaved(perQuery, limit), done);
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
    List<SourceHit> events = new ArrayList<>();
    LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
    for (int i = 0; i < pages.size(); i += EXTRACT_BATCH) {
      if (credits.capReached()) {
        log.info("{}: credit cap reached, {} pages left unread", ID, pages.size() - i);
        return new Reading(events, true);
      }
      List<SourceHit> batch = pages.subList(i, Math.min(pages.size(), i + EXTRACT_BATCH));
      Map<String, SourceHit> byUrl = new LinkedHashMap<>();
      batch.forEach(h -> byUrl.put(h.getCanonicalUrl().getValue(), h));
      JsonNode root;
      try {
        root = post("/extract", extractBody(batch));
      } catch (RuntimeException e) {
        log.warn("{}: a page read failed: {}", ID, e.getClass().getSimpleName());
        continue;
      }
      try {
        for (JsonNode r : root.path("results")) {
          readPage(r, byUrl, today).ifPresent(events::add);
        }
      } catch (LlmUnavailable e) {
        log.info("{}: no model to read pages now ({})", ID, e.getMessage());
        return new Reading(events, true);
      }
    }
    return new Reading(events, false);
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
