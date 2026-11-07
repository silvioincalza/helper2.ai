package ai.helper2.sources.tavily;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.Collector;
import ai.helper2.scout.LlmUnavailable;
import ai.helper2.scout.PageScope;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import ai.helper2.sources.events.EventQuoteGate;
import ai.helper2.sources.events.EventReader;
import ai.helper2.sources.events.EventReading;
import ai.helper2.sources.evidence.TavilyExtract;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Upcoming AI events from the live web (ADR 0067): weekly Tavily searches for AI hackathons,
 * meetups and talks, conferences and workshops; each page found is read with Tavily Extract, a
 * model reads its days, kind, format and place with the words that state them, and {@link
 * EventQuoteGate} decides what is published. An event is listed only when code finds its days in a
 * quote on the page and they start within six months. Its search and Extract credits count on the
 * Scout's daily cap; a pass stops reading when the cap is reached or no model can be called, and
 * keeps what it read.
 */
public class LiveWebEventsCollector implements Collector {

  public static final String ID = "live-web-events-scout";

  /** Circuit breaker and retry of this collector's calls (ADR 0033). */
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
  private final ScoutTavilyCredits credits;
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
      ScoutTavilyCredits credits,
      @Nullable EventReader reader) {
    this.clock = clock;
    this.resilience = resilience;
    this.interval = interval;
    this.queries = List.copyOf(queries);
    this.maxPages = maxPages;
    this.credits = credits.forFeature("scout-events");
    this.reader = reader;
    boolean enabled =
        settings.enabled() && settings.hasToken() && !this.queries.isEmpty() && reader != null;
    if (enabled) {
      resilience.require(POLICY);
    }
    this.http = HttpClients.client(builder, settings);
    this.descriptor =
        new SourceDescriptor(
            ID,
            "Upcoming AI events",
            "Events",
            true,
            SourceMode.INDEXED,
            URI.create("https://helper2.ai/sources"),
            URI.create("https://helper2.ai/terms"),
            "Event pages found by a live web search; an AI model reads each page's days, and code"
                + " checks them against the page's own words. Each links to the event's page.",
            EnumSet.of(ResourceType.EVENT),
            settings.timeout(),
            settings.cacheTtl(),
            enabled,
            48);
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
      return CollectedBatch.skipped(
          "Daily live web credit cap of the Scout reached ("
              + credits.dailyCap()
              + " credits); next try tomorrow");
    }
    List<SourceHit> pages = pagesToRead(startedAt, Math.min(maxPages, Math.max(1, maxItems)));
    List<SourceHit> events = readAll(pages, startedAt);
    log.info("{}: {} pages read, {} events kept", ID, pages.size(), events.size());
    return new CollectedBatch(events, null, startedAt.toString());
  }

  private String notDueReason(@Nullable String checkpoint, Instant now) {
    LocalDate next = interval.nextDueDay(checkpoint, now);
    return next == null ? "Not due yet (run interval)" : "Not due yet: next pass on " + next;
  }

  /**
   * Every query's results that are about AI and have a plain https link, merged by URL, at most
   * [limit]. A search that fails alone is skipped; when half or more fail, the pass fails.
   */
  private List<SourceHit> pagesToRead(Instant now, int limit) {
    Map<String, SourceHit> byUrl = new LinkedHashMap<>();
    RuntimeException lastFailure = null;
    int failed = 0;
    for (String query : queries) {
      try {
        JsonNode root = post("/search", searchBody(query));
        credits.spent(root, SEARCH_DEPTH);
        for (JsonNode r : root.path("results")) {
          SourceHit hit =
              TavilySource.toHit(r, ID, now, byUrl.size(), ResourceType.EVENT, Set.of());
          if (hit != null && PageScope.plainUrlProblem(hit.getUrl()) == null) {
            byUrl.putIfAbsent(
                hit.getCanonicalUrl().getValue(), hit.withFacts(Map.of("query", query)));
          }
        }
      } catch (RuntimeException e) {
        log.warn("{}: a search failed: {}", ID, e.getClass().getSimpleName());
        lastFailure = e;
        failed++;
      }
    }
    if (lastFailure != null && failed * 2 >= queries.size()) {
      throw lastFailure;
    }
    return byUrl.values().stream().limit(limit).toList();
  }

  /** Reads the pages a few at a time; stops when the credit cap is reached or the model is out. */
  private List<SourceHit> readAll(List<SourceHit> pages, Instant now) {
    List<SourceHit> events = new ArrayList<>();
    LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
    for (int i = 0; i < pages.size(); i += EXTRACT_BATCH) {
      if (credits.capReached()) {
        log.info("{}: credit cap reached, {} pages left unread", ID, pages.size() - i);
        break;
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
      credits.spent(root, SEARCH_DEPTH);
      try {
        for (JsonNode r : root.path("results")) {
          readPage(r, byUrl, today).ifPresent(events::add);
        }
      } catch (LlmUnavailable e) {
        log.info("{}: no model to read pages now ({})", ID, e.getMessage());
        break;
      }
    }
    return events;
  }

  /** One extracted page: the event the gate lets through, if any. */
  private Optional<SourceHit> readPage(JsonNode r, Map<String, SourceHit> byUrl, LocalDate today) {
    String url = HttpClients.text(r, "url");
    String text = HttpClients.text(r, "raw_content");
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

  /** The search result as an EVENT with the checked days and the evidence for them. */
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
            TavilyExtract.requestBody(pages.stream().map(SourceHit::getUrl).toList()));
    body.put("include_usage", true);
    return body;
  }

  private JsonNode post(String path, Map<String, Object> body) {
    return resilience.call(
        POLICY,
        () ->
            HttpClients.getJson(
                () ->
                    http.post()
                        .uri(path)
                        .attributes(ObservedHttp.maxResponseBytes(MAX_EXTRACT_BYTES))
                        .body(body)
                        .retrieve()
                        .body(String.class),
                clock));
  }
}
