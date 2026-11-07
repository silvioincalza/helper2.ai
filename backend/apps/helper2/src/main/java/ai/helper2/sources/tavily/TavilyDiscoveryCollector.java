package ai.helper2.sources.tavily;

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
import ai.helper2.shared.SourceMode;
import ai.helper2.sources.support.Classification;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.sources.tavily.DiscoveryPages.Page;
import ai.helper2.sources.tavily.DiscoveryPages.Shape;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
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

/**
 * Tavily discovery (ADR 0051) for one category of the AI world: its searches on the general web, on
 * its own interval (weekly by default; the categories start on different days of it, by their place
 * in the list, so about 5 search a day). Each result is sorted by code, never by a model ({@link
 * DiscoveryPages}): a product's own site or a GitHub repository is a candidate for the admin review
 * list, a list or comparison page is indexed as an ARTICLE (unless the category's index-lists is
 * off), anything else is dropped.
 *
 * <p>A result gets the tag of this category and of every category whose words are in its title or
 * excerpt. With require-keyword, a result is kept only when this category's own words are in it.
 * Each kept result carries what the search said about it (query, rank, score, depth, shape,
 * published date, request id, the words that matched): saved in the record's extra, never shown. No
 * page text beyond Tavily's excerpt, no images and no Tavily answer are asked for.
 *
 * <p>A search that fails alone is logged and skipped, so the searches already paid for are kept;
 * when half or more fail (an outage), the pass fails and its checkpoint stays. A pass asks only for
 * pages published since the previous complete pass (start_date), or the last month on the first.
 * Every search counts its credits ({@link ScoutTavilyCredits}); past the Scout's daily cap the
 * collector skips. Docs: https://docs.tavily.com/documentation/api-reference/endpoint/search
 */
public class TavilyDiscoveryCollector implements Collector {

  private static final Logger log = LoggerFactory.getLogger(TavilyDiscoveryCollector.class);

  /** Circuit breaker and retry of every discovery collector's calls, shared (ADR 0033). */
  static final String POLICY = "collector-discovery";

  /**
   * The most of Tavily's excerpt a product candidate keeps, as the evidence its enrichment cites.
   */
  static final int MAX_EVIDENCE = 2400;

  /** What to send Tavily for every category: the depth and the number of results. */
  public record Search(String searchDepth, int maxResults) {}

  /** One kept result, merged across this category's searches by URL. */
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
  private final ScoutTavilyCredits credits;
  private final DiscoveryHostStore hosts;
  private final SourceDescriptor descriptor;

  /**
   * @param all every category, this one included: a result gets the tag of each whose words it has
   */
  public TavilyDiscoveryCollector(
      SourceSettings settings,
      RestClient.Builder builder,
      Clock clock,
      Resilience resilience,
      Search search,
      DiscoveryCategory category,
      List<DiscoveryCategory> all,
      ScoutTavilyCredits credits,
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
    this.descriptor =
        new SourceDescriptor(
            category.sourceId(),
            category.name(),
            category.shortName(),
            true,
            SourceMode.INDEXED,
            URI.create("https://helper2.ai/sources"),
            URI.create("https://helper2.ai/terms"),
            category.indexLists()
                ? "Web results found by a live web search; each links to the original publisher."
                    + " List articles are added without review; products and articles about them"
                    + " are added only after a person checks them."
                : "Web results found by a live web search; each links to the original publisher."
                    + " Products and articles about them are added only after a person checks"
                    + " them.",
            resourceTypes(category),
            settings.timeout(),
            settings.cacheTtl(),
            enabled && settings.hasToken(),
            50,
            false,
            true,
            false,
            false,
            false,
            category.family().id());
  }

  /** The category's product type, and the articles about them (list pages, posts: ADR 0062). */
  private static Set<ResourceType> resourceTypes(DiscoveryCategory category) {
    return EnumSet.of(category.type(), ResourceType.ARTICLE);
  }

  @Override
  public SourceDescriptor getDescriptor() {
    return descriptor;
  }

  public DiscoveryCategory category() {
    return category;
  }

  /** When its passes are due: its category's interval, from its own first day. */
  public RunInterval interval() {
    return interval;
  }

  @Override
  public CollectedBatch collect(
      @Nullable String checkpoint, @Nullable String resumeCursor, int maxItems) {
    return collect(new ScoutCursor(checkpoint, resumeCursor, null), maxItems);
  }

  /**
   * A pass that is open (its first try failed or met the cap) runs on the next run, whatever the
   * day: the stagger only delays a category's first try.
   */
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
    // The host lists as they are now (ADR 0063); a failed read fails the run before any search.
    DiscoveryPages pages = new DiscoveryPages(hosts.lists());
    Map<String, Found> found = searchAll(pages, TavilyCollector.startDate(checkpoint), startedAt);
    return new CollectedBatch(
        found.values().stream()
            .filter(f -> f.page().shape() == Shape.LIST)
            .map(f -> listHit(f, startedAt))
            .filter(Objects::nonNull)
            .toList(),
        null,
        startedAt.toString(),
        false,
        found.values().stream()
            .filter(f -> f.page().shape() != Shape.LIST)
            .map(this::candidate)
            .toList());
  }

  private String notDueReason(@Nullable String checkpoint, Instant now) {
    LocalDate next = interval.nextDueDay(checkpoint, now);
    return next == null ? "Not due yet (run interval)" : "Not due yet: next pass on " + next;
  }

  /**
   * Every query of the category, merged by URL. A search that fails alone is skipped, so the paid
   * ones are kept; when half or more fail, the pass fails and its checkpoint stays.
   */
  private Map<String, Found> searchAll(
      DiscoveryPages pages, @Nullable String since, Instant searchedAt) {
    Map<String, Found> found = new LinkedHashMap<>();
    RuntimeException lastFailure = null;
    int failed = 0;
    for (String query : category.queries()) {
      try {
        JsonNode root = searchFor(query, since);
        credits.spent(root, search.searchDepth());
        sort(pages, root, query, searchedAt)
            .forEach(f -> found.putIfAbsent(f.page().url().getValue(), f));
      } catch (RuntimeException e) {
        log.warn("{}: a search failed: {}", category.sourceId(), e.getClass().getSimpleName());
        lastFailure = e;
        failed++;
      }
    }
    // A circuit that opens mid-pass fails the rest fast: keep the checkpoint, so the next run
    // searches these pages again, rather than losing them.
    if (lastFailure != null && failed * 2 >= category.queries().size()) {
      throw lastFailure;
    }
    return found;
  }

  private JsonNode searchFor(String query, @Nullable String since) {
    Map<String, Object> body = requestBody(query, since);
    return resilience.call(
        POLICY,
        () ->
            HttpClients.getJson(
                () -> http.post().uri("/search").body(body).retrieve().body(String.class), clock));
  }

  /**
   * The search request: general web, the query, only pages new since [since]. Asks for the
   * published date, the favicon URL and the credits used; never for page text, images or an answer.
   */
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

  /** One search's kept results, in Tavily's order. */
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

  /**
   * The result as a list page, a product site or an article, or null when it is dropped: below the
   * score floor, not about AI, none of the shapes, a list page while lists are off, or without this
   * category's words while they are required.
   */
  private @Nullable Found keep(
      DiscoveryPages pages, JsonNode root, JsonNode r, int rank, String query, Instant searchedAt) {
    double score = r.path("score").asDouble(0);
    String title = HttpClients.text(r, "title");
    CanonicalUrl url = CanonicalUrl.of(HttpClients.text(r, "url"));
    String content = HttpClients.text(r, "content");
    if (score < category.minScore()
        || title == null
        || url == null
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

  /** Per category whose words are in [text]: the words found. */
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

  /**
   * At most {@link Candidate#MAX_TAGS} of a product's tags, so an admin can accept every candidate:
   * this category first, then the ones whose words it has, in the configured order. Sorted.
   */
  Set<String> candidateTags(Set<String> all) {
    return Stream.concat(
            Stream.of(category.id()),
            this.all.stream().map(DiscoveryCategory::id).filter(all::contains))
        .distinct()
        .limit(Candidate.MAX_TAGS)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  /** Tavily's excerpt, up to [MAX_EVIDENCE] characters. */
  static @Nullable String evidence(@Nullable String content) {
    if (content == null) {
      return null;
    }
    String text = content.strip();
    return text.length() > MAX_EVIDENCE ? text.substring(0, MAX_EVIDENCE - 1) + "…" : text;
  }
}
