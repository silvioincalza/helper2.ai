package ai.helper2.sources.liveweb.scout;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.Collector;
import ai.helper2.scout.DiscoveryHostStore;
import ai.helper2.scout.ScoutCursor;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.config.CollectorDescriptors;
import ai.helper2.sources.liveweb.scout.DiscoveryPages.Page;
import ai.helper2.sources.liveweb.scout.DiscoveryPages.Shape;
import ai.helper2.sources.support.Classification;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.sources.tavily.DiscoveryCategory;
import ai.helper2.sources.tavily.TavilySource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public class DiscoveryCollector implements Collector {

  private static final Logger log = LoggerFactory.getLogger(DiscoveryCollector.class);

  static final String POLICY = "collector-discovery";

  static final int MAX_EVIDENCE = 2400;

  public record Search(String searchDepth, int maxResults) {}

  private record Found(
      Page page, JsonNode result, int rank, @Nullable String content, Map<String, Object> facts) {

    @SuppressWarnings("unchecked")
    Set<String> tags() {
      return Set.copyOf((List<String>) facts.get("categories"));
    }
  }

  private final RestClient http;
  private final Clock clock;
  private final Resilience resilience;
  private final RunInterval interval;
  private final Search search;
  private final DiscoveryCategory category;
  private final List<DiscoveryCategory> all;
  private final ScoutLiveWebCredits credits;
  private final DiscoveryHostStore hosts;
  private final SourceDescriptor descriptor;

  public DiscoveryCollector(
      SourceSettings settings,
      RestClient.Builder builder,
      Clock clock,
      Resilience resilience,
      Search search,
      DiscoveryCategory category,
      List<DiscoveryCategory> all,
      ScoutLiveWebCredits credits,
      DiscoveryHostStore hosts) {
    this.clock = clock;
    this.resilience = resilience;
    boolean enabled = settings.enabled() && category.enabled();
    if (enabled) {
      resilience.require(POLICY);
    }
    this.interval = RunInterval.staggered(category.interval(), all.indexOf(category));
    this.search = search;
    this.category = category;
    this.all = List.copyOf(all);
    this.credits = credits;
    this.hosts = hosts;
    this.http = HttpClients.client(builder, settings);
    this.descriptor = CollectorDescriptors.discovery(settings, category);
  }

  @Override
  public SourceDescriptor getDescriptor() {
    return descriptor;
  }

  public DiscoveryCategory category() {
    return category;
  }

  public RunInterval interval() {
    return interval;
  }

  @Override
  public CollectedBatch collect(
      @Nullable String checkpoint, @Nullable String resumeCursor, int maxItems) {
    return collect(new ScoutCursor(checkpoint, resumeCursor, null), maxItems);
  }

  @Override
  public CollectedBatch collect(ScoutCursor cursor, int maxItems) {
    String checkpoint = cursor.getCheckpoint();
    Instant startedAt = clock.instant();
    boolean open = cursor.getResumeCursor() != null || cursor.getPassStartedAt() != null;
    if (!open && !interval.isDue(checkpoint, startedAt)) {
      return CollectedBatch.skipped(notDueReason(checkpoint, startedAt));
    }
    if (credits.capReached()) {
      return CollectedBatch.deferred(
          "Daily live web credit cap of the Scout reached ("
              + credits.dailyCap()
              + " credits); next try tomorrow");
    }
    DiscoveryPages pages = new DiscoveryPages(hosts.lists());
    Searched searched =
        searchAll(
            pages,
            LiveWebNewsCollector.startDate(checkpoint),
            startedAt,
            QueryProgress.done(cursor.getResumeCursor()));
    Collection<Found> found = searched.found().values();
    return new CollectedBatch(
        found.stream()
            .filter(f -> f.page().shape() == Shape.LIST)
            .map(f -> listHit(f, startedAt))
            .filter(Objects::nonNull)
            .toList(),
        searched.complete() ? null : QueryProgress.cursor(searched.done()),
        searched.complete() ? startedAt.toString() : null,
        false,
        found.stream().filter(f -> f.page().shape() != Shape.LIST).map(this::candidate).toList());
  }

  private String notDueReason(@Nullable String checkpoint, Instant now) {
    LocalDate next = interval.nextDueDay(checkpoint, now);
    return next == null ? "Not due yet (run interval)" : "Not due yet: next pass on " + next;
  }

  private record Searched(Map<String, Found> found, Set<String> done, boolean complete) {}

  private Searched searchAll(
      DiscoveryPages pages, @Nullable String since, Instant searchedAt, Set<String> done) {
    Map<String, Found> found = new LinkedHashMap<>();
    RuntimeException lastFailure = null;
    int failed = 0;
    for (String query : category.queries()) {
      if (done.contains(query)) {
        continue;
      }
      if (credits.capReached()) {
        log.info("{}: credit cap reached, the pass stays open", category.sourceId());
        break;
      }
      try {
        sort(pages, searchFor(query, since), query, searchedAt)
            .forEach(f -> found.putIfAbsent(f.page().url().getValue(), f));
        done.add(query);
      } catch (RuntimeException e) {
        log.warn("{}: a search failed: {}", category.sourceId(), e.getClass().getSimpleName());
        lastFailure = e;
        failed++;
      }
    }
    if (lastFailure != null && failed * 2 >= category.queries().size()) {
      throw lastFailure;
    }
    return new Searched(found, done, done.containsAll(category.queries()));
  }

  private JsonNode searchFor(String query, @Nullable String since) {
    Map<String, Object> body = requestBody(query, since);
    return resilience.call(
        POLICY,
        () ->
            credits.countedAttempt(
                search.searchDepth(),
                () ->
                    HttpClients.getJson(
                        () -> http.post().uri("/search").body(body).retrieve().body(String.class),
                        clock)));
  }

  Map<String, Object> requestBody(String query, @Nullable String since) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("query", query);
    body.put("topic", "general");
    body.put("search_depth", search.searchDepth());
    body.put("max_results", search.maxResults());
    body.put("include_answer", false);
    body.put("include_raw_content", false);
    body.put("include_images", false);
    body.put("include_published_date", true);
    body.put("include_favicon", true);
    body.put("include_usage", true);
    if (!category.excludeDomains().isEmpty()) {
      body.put("exclude_domains", category.excludeDomains());
    }
    if (since != null) {
      body.put("start_date", since);
    } else {
      body.put("time_range", "month");
    }
    return body;
  }

  private List<Found> sort(DiscoveryPages pages, JsonNode root, String query, Instant searchedAt) {
    List<Found> kept = new ArrayList<>();
    int position = 0;
    for (JsonNode r : root.path("results")) {
      Found f = keep(pages, root, r, position++, query, searchedAt);
      if (f != null) {
        kept.add(f);
      }
    }
    return kept;
  }

  private @Nullable Found keep(
      DiscoveryPages pages, JsonNode root, JsonNode r, int rank, String query, Instant searchedAt) {
    double score = r.path("score").asDouble(0);
    String title = HttpClients.text(r, "title");
    CanonicalUrl url = CanonicalUrl.of(HttpClients.text(r, "url"));
    String content = HttpClients.text(r, "content");
    if (score < category.minScore()
        || title == null
        || url == null
        || TavilySource.isTavilysOwnPage(url)
        || !Classification.isAiRelated(title, content, Set.of())) {
      return null;
    }
    Page page = pages.classify(url, title);
    if (page == null || (page.shape() == Shape.LIST && !category.indexLists())) {
      return null;
    }
    Map<String, List<String>> matched =
        matchedWords(title + " " + (content == null ? "" : content));
    if (category.requireKeyword() && !matched.containsKey(category.id())) {
      return null;
    }
    Map<String, Object> discovery = new LinkedHashMap<>();
    discovery.put("category", category.id());
    discovery.put("query", query);
    discovery.put("rank", rank);
    discovery.put("score", score);
    discovery.put("searchDepth", search.searchDepth());
    discovery.put("shape", page.shape().name());
    putText(discovery, "publishedDate", HttpClients.text(r, "published_date"));
    putText(discovery, "requestId", HttpClients.text(root, "request_id"));
    discovery.put("searchedAt", searchedAt.toString());
    discovery.put("matchedWords", matched);
    putText(discovery, "favicon", HttpClients.text(r, "favicon"));
    Set<String> categories = new TreeSet<>(matched.keySet());
    categories.add(category.id());
    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put("discovery", discovery);
    facts.put("categories", List.copyOf(categories));
    return new Found(page, r, rank, content, facts);
  }

  private Map<String, List<String>> matchedWords(String text) {
    Map<String, List<String>> matched = new TreeMap<>();
    for (DiscoveryCategory c : all) {
      List<String> words = c.words().matchedIn(text);
      if (!words.isEmpty()) {
        matched.put(c.id(), words);
      }
    }
    return matched;
  }

  private static void putText(Map<String, Object> map, String key, @Nullable String value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  private @Nullable SourceHit listHit(Found f, Instant now) {
    SourceHit hit =
        TavilySource.toHit(
            f.result(), category.sourceId(), now, f.rank(), ResourceType.ARTICLE, f.tags());
    return hit == null ? null : hit.withFacts(f.facts());
  }

  private Candidate candidate(Found f) {
    Set<String> tags = candidateTags(f.tags());
    return new Candidate(
        f.page().url(),
        f.page().title(),
        evidence(f.content()),
        String.join(", ", tags),
        f.page().shape() == Shape.ARTICLE ? ResourceType.ARTICLE : category.type(),
        tags,
        f.facts());
  }

  Set<String> candidateTags(Set<String> all) {
    return Stream.concat(
            Stream.of(category.id()),
            this.all.stream().map(DiscoveryCategory::id).filter(all::contains))
        .distinct()
        .limit(Candidate.MAX_TAGS)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  static @Nullable String evidence(@Nullable String content) {
    if (content == null) {
      return null;
    }
    String text = content.strip();
    return text.length() > MAX_EVIDENCE ? text.substring(0, MAX_EVIDENCE - 1) + "…" : text;
  }
}
