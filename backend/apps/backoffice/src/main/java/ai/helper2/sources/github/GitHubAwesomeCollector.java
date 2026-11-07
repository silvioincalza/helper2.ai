package ai.helper2.sources.github;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.Collector;
import ai.helper2.scout.DiscoveryHostStore;
import ai.helper2.scout.ListEntryText;
import ai.helper2.scout.ListText;
import ai.helper2.search.PopularityKind;
import ai.helper2.search.PopularityMetric;
import ai.helper2.search.ResultMerger;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceFailure;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.CategoryInput;
import ai.helper2.shared.CategoryMatcher;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.sources.classifier.RepoClassifier;
import ai.helper2.sources.config.CollectorDescriptors;
import ai.helper2.sources.support.Classification;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.sources.tavily.DiscoveryPages;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * GitHub "awesome" lists about AI, found by topic search. A pass: discover the lists, read each
 * README as a link graph ({@link AwesomeListParser}), fetch facts for the linked repositories with
 * GraphQL (50 per call), let the fast model decide which are AI resources and of what type, and
 * keep those. Bounded per run ({@code maxEntriesPerRun}) and per pass ({@code maxEntriesPerPass});
 * an unfinished pass resumes from an entry offset. The lists are discovered again on resume, so if
 * their order changed in between a few entries may wait until the next pass; upserts are
 * idempotent, so nothing is duplicated. Needs a GitHub token (GraphQL rejects anonymous calls):
 * without one the collector is disabled.
 *
 * <p>Search inside the lists (ADR 0010): every run also returns each list it read as a resource
 * (ARTICLE, tag {@value #LIST_TAG}) and the list's search text (entry lines and headings, cleaned
 * and capped), which the Scout hands to the catalog's list index.
 *
 * <p>Deep search (ADR 0022): a run also follows the awesome lists the seed lists link to ({@link
 * AwesomeCrawl}, up to {@link Deep#maxDepth} steps and {@link Deep#maxNestedLists} lists, within a
 * time and size budget); they become list resources like the seeds. The first link of an entry that
 * points outside GitHub at a product's own site ({@link ProductLinks}) becomes a candidate for the
 * admin review list, never indexed straight away, at most {@link Deep#linksPerDay} new items a day.
 * Each entry's section headings give its categories ({@link CategoryMatcher}).
 */
public class GitHubAwesomeCollector implements Collector {

  static final String ID = CollectorDescriptors.GITHUB_AWESOME;

  /** Circuit breaker and retry of this collector\'s calls (ADR 0006). */
  static final String POLICY = "collector-" + ID;

  private static final Logger log = LoggerFactory.getLogger(GitHubAwesomeCollector.class);
  private static final int GRAPHQL_BATCH = 50;
  private static final int SEARCH_PAGE = 20;

  public record Limits(
      int maxLists,
      int minListStars,
      int maxEntriesPerPass,
      int maxEntriesPerRun,
      int minRepoStars,
      int classifyBatch) {}

  /**
   * Deep search (ADR 0022): how far nested lists are followed, the time and README bytes one run
   * may spend, and the links to product sites proposed for review.
   *
   * @param excludeDomains domains whose pages are never a product's own site (directories, review,
   *     social and video sites), subdomains included
   */
  public record Deep(
      int maxDepth,
      int maxNestedLists,
      Duration maxRunTime,
      long maxReadmeBytesPerRun,
      int maxLinksPerPass,
      int linksPerDay,
      List<String> excludeDomains) {

    public Deep {
      excludeDomains = List.copyOf(excludeDomains);
    }
  }

  private final RestClient http;
  private final Clock clock;
  private final Resilience resilience;
  private final RunInterval interval;
  private final List<String> topicQueries;
  private final Limits limits;
  private final RepoClassifier classifier;
  private final RepoClassifier fallback = new RepoClassifier.Heuristic();
  private final SourceDescriptor descriptor;
  private final Deep deep;
  private final ProductLinks productLinks;
  private final CategoryMatcher categories;
  private final DiscoveryHostStore hosts;
  private final AwesomeMetrics metrics;

  public GitHubAwesomeCollector(
      SourceSettings settings,
      RestClient.Builder builder,
      Clock clock,
      Resilience resilience,
      RunInterval interval,
      List<String> topicQueries,
      Limits limits,
      Deep deep,
      RepoClassifier classifier,
      CategoryMatcher categories,
      MeterRegistry meters,
      DiscoveryHostStore hosts) {
    this.clock = clock;
    this.resilience = resilience;
    if (settings.enabled()) {
      resilience.require(POLICY);
    }
    this.interval = interval;
    this.topicQueries = topicQueries;
    this.limits = limits;
    this.classifier = classifier;
    this.deep = deep;
    this.productLinks = new ProductLinks(deep.excludeDomains());
    this.categories = categories;
    this.hosts = hosts;
    this.metrics = new AwesomeMetrics(meters);
    this.http = HttpClients.gitHubClient(builder, settings);
    this.descriptor = CollectorDescriptors.gitHubAwesome(settings);
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
    // Discovery's host lists as they are now (ADR 0022); a failed read fails the run first.
    DiscoveryPages pages = new DiscoveryPages(hosts.lists());
    Pass pass = read(discoverListRepos());
    List<AwesomeListParser.Entry> all = pass.entries();
    int offset = offset(resumeCursor);
    List<SourceHit> listHits = listHits(pass.read(), startedAt);
    // Proposed once per pass, on its first run: a resumed run reads the same links again.
    List<Candidate> candidates = offset == 0 ? candidates(pass.crawl(), pages) : List.of();
    if (offset >= all.size()) {
      return batch(listHits, null, startedAt, candidates, pass.texts());
    }
    int end =
        Math.min(all.size(), offset + Math.max(1, Math.min(maxItems, limits.maxEntriesPerRun())));
    List<AwesomeListParser.Entry> slice = all.subList(offset, end);
    List<SourceHit> hits = new ArrayList<>(toHits(slice, repoFacts(slice), offset, startedAt));
    log.info(
        "awesome lists: entries {}-{} of {}, {} kept as AI resources (classifier {})",
        offset,
        end,
        all.size(),
        hits.size(),
        classifier.name());
    hits.addAll(listHits);
    return batch(
        hits, end < all.size() ? String.valueOf(end) : null, startedAt, candidates, pass.texts());
  }

  private CollectedBatch batch(
      List<SourceHit> hits,
      @Nullable String nextCursor,
      Instant startedAt,
      List<Candidate> candidates,
      List<ListText> texts) {
    return new CollectedBatch(
        hits,
        nextCursor,
        startedAt.toString(),
        false,
        candidates,
        null,
        texts,
        false,
        deep.linksPerDay());
  }

  /** Names of the lists a pass reads, most stars first. */
  List<String> discoverLists() {
    return discoverListRepos().stream().map(ListRepo::fullName).toList();
  }

  /** A list found by topic search, with the repository facts the search answer gives. */
  record ListRepo(String fullName, JsonNode facts) {}

  /**
   * Lists by stars, most first. A search query that fails is skipped; rate limits stop the pass.
   */
  List<ListRepo> discoverListRepos() {
    Map<String, Long> stars = new HashMap<>();
    Map<String, JsonNode> facts = new HashMap<>();
    for (String topics : topicQueries) {
      String q = topics + " fork:false archived:false stars:>=" + limits.minListStars();
      JsonNode root;
      try {
        root =
            resilience.call(
                POLICY,
                () ->
                    HttpClients.getJson(
                        () ->
                            http.get()
                                .uri(u ->
                                    u.path("/search/repositories")
                                        .queryParam("q", q)
                                        .queryParam("sort", "stars")
                                        .queryParam("order", "desc")
                                        .queryParam("per_page", SEARCH_PAGE)
                                        .build())
                                .retrieve()
                                .body(String.class),
                        clock));
      } catch (SourceFailure.Unavailable e) {
        log.warn("awesome list search '{}' failed: {}", topics, e.getMessage());
        continue;
      }
      for (JsonNode item : root.path("items")) {
        String name = HttpClients.text(item, "full_name");
        // Topic search alone returns prompt sites, security handbooks and apps; awesome lists say
        // so in the name.
        if (name != null && name.toLowerCase(Locale.ROOT).contains("awesome")) {
          stars.merge(name, item.path("stargazers_count").asLong(0), Math::max);
          facts.putIfAbsent(name, item);
        }
      }
    }
    return stars.entrySet().stream()
        .sorted(
            Map.Entry.<String, Long>comparingByValue()
                .reversed()
                .thenComparing(Map.Entry.comparingByKey()))
        .limit(limits.maxLists())
        .map(e -> new ListRepo(e.getKey(), facts.get(e.getKey())))
        .toList();
  }

  /**
   * What one pass read: the entries to collect (unique across lists, at most maxEntriesPerPass, the
   * lists read left out), the lists whose README was read, their search text (ADR 0010), and every
   * list read with its depth (ADR 0022).
   */
  record Pass(
      List<AwesomeListParser.Entry> entries,
      List<ListRepo> read,
      List<ListText> texts,
      List<AwesomeCrawl.Read> crawl) {}

  /**
   * Per list at most this much entry text and heading path goes to the search index (UTF-8 bytes),
   * on top of the per-list entry cap (maxEntriesPerPass).
   */
  static final int MAX_LIST_TEXT_BYTES = 256 * 1024;

  static final String LIST_TAG = "awesome-list";

  private static final String GITHUB = "https://github.com/";

  private Pass read(List<ListRepo> seeds) {
    List<AwesomeCrawl.Read> crawl =
        AwesomeCrawl.crawl(seeds, gitHubLists(), crawlLimits(), clock, metrics);
    Set<String> lists = new HashSet<>();
    crawl.forEach(r -> lists.add(r.list().fullName().toLowerCase(Locale.ROOT)));
    Map<String, AwesomeListParser.Entry> unique = new LinkedHashMap<>();
    List<ListText> texts = new ArrayList<>();
    for (AwesomeCrawl.Read r : crawl) {
      ListText text = searchText(r.list(), r.parsed());
      if (text != null) {
        texts.add(text);
      }
      for (AwesomeListParser.Entry e : r.parsed().entries()) {
        String key = e.fullName().toLowerCase(Locale.ROOT);
        // A list that was read is a list resource of its own, not an entry to classify.
        if (unique.size() < limits.maxEntriesPerPass() && !lists.contains(key)) {
          unique.putIfAbsent(key, e);
        }
      }
    }
    List<ListRepo> read = crawl.stream().map(AwesomeCrawl.Read::list).toList();
    return new Pass(List.copyOf(unique.values()), read, texts, crawl);
  }

  private AwesomeCrawl.Limits crawlLimits() {
    return new AwesomeCrawl.Limits(
        deep.maxDepth(),
        deep.maxNestedLists(),
        deep.maxRunTime(),
        deep.maxReadmeBytesPerRun(),
        limits.minRepoStars());
  }

  /** The GitHub calls the crawl makes, under this collector's resilience policy. */
  private AwesomeCrawl.Lists gitHubLists() {
    return new AwesomeCrawl.Lists() {
      @Override
      public JsonNode repo(String fullName) {
        return repoFacts(fullName);
      }

      @Override
      public String readme(String fullName) {
        return GitHubAwesomeCollector.this.readme(fullName);
      }
    };
  }

  /** A repository's REST facts: the same fields as a search answer's item. */
  private JsonNode repoFacts(String fullName) {
    String[] parts = fullName.split("/", 2);
    return resilience.call(
        POLICY,
        () ->
            HttpClients.getJson(
                () ->
                    http.get()
                        .uri("/repos/{owner}/{repo}", parts[0], parts[1])
                        .retrieve()
                        .body(String.class),
                clock));
  }

  /**
   * The links of every list read that point at a product's own site (ADR 0022), each site once, in
   * the order the lists were read, at most {@link Deep#maxLinksPerPass}. Every skip is counted.
   */
  private List<Candidate> candidates(List<AwesomeCrawl.Read> crawl, DiscoveryPages pages) {
    Map<String, Candidate> found = new LinkedHashMap<>();
    for (AwesomeCrawl.Read r : crawl) {
      CanonicalUrl list = CanonicalUrl.of(GITHUB + r.list().fullName());
      for (AwesomeListParser.Link link : r.parsed().links()) {
        ProductLinks.Checked checked = productLinks.check(link.url(), pages);
        if (checked.site() == null) {
          metrics.skipped("link-" + checked.skip().tag());
        } else if (found.containsKey(checked.site().getValue())) {
          metrics.skipped("link-seen");
        } else if (found.size() >= deep.maxLinksPerPass()) {
          metrics.skipped("link-cap");
        } else if (list != null) {
          found.put(checked.site().getValue(), candidate(checked.site(), link, list));
        }
      }
    }
    metrics.proposed(found.size());
    return List.copyOf(found.values());
  }

  /**
   * A product site from a list: never indexed, a person decides (ADR 0022). Its title is the link's
   * words (the product's name), no snippet (the list's text is not kept). Its categories come from
   * the section headings and the name, at most {@link Candidate#MAX_TAGS}; foundBy keeps the list
   * and the section.
   */
  private Candidate candidate(CanonicalUrl site, AwesomeListParser.Link link, CanonicalUrl list) {
    String title = link.text().isBlank() ? site.getHost() : link.text();
    Set<String> tags =
        new LinkedHashSet<>(
            categories.match(
                new CategoryInput(
                    title, null, List.of(), null, List.of(), List.of(link.headingPath()))));
    Set<String> capped = new LinkedHashSet<>(tags.stream().limit(Candidate.MAX_TAGS).toList());
    Map<String, Object> awesome = new LinkedHashMap<>();
    awesome.put("list", list.getValue());
    awesome.put("section", link.headingPath());
    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put(FOUND_IN, awesome);
    facts.put("categories", List.copyOf(capped));
    return new Candidate(
        site,
        title,
        null,
        capped.isEmpty() ? null : String.join(", ", capped),
        ResourceType.PRODUCT,
        capped,
        facts);
  }

  /** The candidate fact that says where a link was found: its list and section. */
  static final String FOUND_IN = "awesome";

  /** The list's search text, capped; null when its page has no canonical URL. */
  private ListText searchText(ListRepo list, AwesomeListParser.Parsed parsed) {
    CanonicalUrl url = CanonicalUrl.of(GITHUB + list.fullName());
    if (url == null) {
      return null;
    }
    List<ListEntryText> entries = new ArrayList<>();
    for (AwesomeListParser.Entry e :
        parsed.searchEntries(limits.maxEntriesPerPass(), MAX_LIST_TEXT_BYTES)) {
      CanonicalUrl entryUrl = CanonicalUrl.of(GITHUB + e.fullName());
      if (entryUrl != null) {
        entries.add(new ListEntryText(entryUrl, e.text(), e.headingPath()));
      }
    }
    return new ListText(url, parsed.headingsText(), entries);
  }

  /**
   * The lists themselves, as resources a search can find (ADR 0010): an awesome list is reading
   * about AI tools, so ARTICLE, tagged {@link #LIST_TAG}. Facts from the search answer only.
   */
  private static List<SourceHit> listHits(List<ListRepo> lists, Instant now) {
    List<SourceHit> hits = new ArrayList<>();
    for (ListRepo list : lists) {
      SourceHit hit = listHit(list, now);
      if (hit != null) {
        hits.add(hit);
      }
    }
    return hits;
  }

  private static SourceHit listHit(ListRepo list, Instant now) {
    JsonNode repo = list.facts();
    String url = HttpClients.text(repo, "html_url");
    CanonicalUrl canonical = CanonicalUrl.of(url == null ? GITHUB + list.fullName() : url);
    if (canonical == null) {
      return null;
    }
    String spdx = HttpClients.text(repo.path("license"), "spdx_id");
    String license = spdx == null ? null : ResultMerger.normalizeLicense(spdx);
    Set<String> tags = new LinkedHashSet<>();
    tags.add(LIST_TAG);
    repo.path("topics")
        .forEach(t -> {
          if (t.isString()) {
            tags.add(t.asString());
          }
        });
    return new SourceHit(
        ID,
        list.fullName(),
        URI.create(canonical.getValue()),
        canonical,
        null,
        list.fullName(),
        HttpClients.text(repo, "description"),
        ValueOrigin.SOURCE_PROVIDED,
        ResourceType.ARTICLE,
        Audience.BOTH,
        null,
        Set.of(),
        tags,
        license,
        null,
        Set.of(),
        Classification.openSource(license),
        DateFact.fromSource(HttpClients.instant(repo, "created_at"), "github", now),
        DateFact.fromSource(HttpClients.instant(repo, "pushed_at"), "github", now),
        List.of(
            new PopularityMetric(
                PopularityKind.GITHUB_STARS,
                repo.path("stargazers_count").asLong(),
                "github",
                now)),
        now,
        0);
  }

  /** Big awesome lists are long Markdown files; anything past this is not a list worth parsing. */
  static final int README_MAX_BYTES = 5 * 1024 * 1024;

  /**
   * Total time for one README (ADR 0006 (part was 0006) §2): 5 MB at about 90 kB/s; other calls
   * keep the collector's deadline.
   */
  static final Duration README_DEADLINE = Duration.ofSeconds(60);

  private String readme(String list) {
    String[] parts = list.split("/", 2);
    return resilience.call(
        POLICY,
        () ->
            HttpClients.getText(
                () ->
                    http.get()
                        .uri("/repos/{owner}/{repo}/readme", parts[0], parts[1])
                        .header("Accept", "application/vnd.github.raw+json")
                        .attributes(ObservedHttp.maxResponseBytes(README_MAX_BYTES))
                        .attributes(ObservedHttp.deadline(README_DEADLINE))
                        .retrieve()
                        .body(String.class),
                clock));
  }

  /**
   * Facts by lower-case full name. Repositories GitHub cannot resolve (renamed, deleted) are
   * absent.
   */
  Map<String, JsonNode> repoFacts(List<AwesomeListParser.Entry> entries) {
    Map<String, JsonNode> facts = new HashMap<>();
    for (int from = 0; from < entries.size(); from += GRAPHQL_BATCH) {
      List<AwesomeListParser.Entry> batch =
          entries.subList(from, Math.min(entries.size(), from + GRAPHQL_BATCH));
      ObjectNode body = JsonNodeFactory.instance.objectNode().put("query", graphQl(batch));
      JsonNode root =
          resilience.call(
              POLICY,
              () ->
                  HttpClients.getJson(
                      () ->
                          http.post()
                              .uri("/graphql")
                              .header("Content-Type", "application/json")
                              .body(body.toString())
                              .retrieve()
                              .body(String.class),
                      clock));
      for (JsonNode error : root.path("errors")) {
        if ("RATE_LIMITED".equals(error.path("type").asString(""))) {
          throw new SourceFailure.RateLimited(null);
        }
      }
      JsonNode data = root.path("data");
      if (!data.isObject()) {
        throw new SourceFailure.Unavailable("GraphQL answer without data");
      }
      for (int i = 0; i < batch.size(); i++) {
        JsonNode repo = data.path("r" + i);
        if (repo.isObject()) {
          facts.put(batch.get(i).fullName().toLowerCase(Locale.ROOT), repo);
        }
      }
    }
    return facts;
  }

  static String graphQl(List<AwesomeListParser.Entry> batch) {
    StringBuilder q = new StringBuilder("query {");
    for (int i = 0; i < batch.size(); i++) {
      String[] parts = batch.get(i).fullName().split("/", 2);
      q.append(" r")
          .append(i)
          .append(": repository(owner: \"")
          .append(parts[0])
          .append("\", name: \"")
          .append(parts[1])
          .append("\") { nameWithOwner url description stargazerCount isArchived isFork")
          .append(
              " createdAt pushedAt licenseInfo { spdxId } repositoryTopics(first: 20) { nodes { topic { name } } } }");
    }
    return q.append(" }").toString();
  }

  private List<SourceHit> toHits(
      List<AwesomeListParser.Entry> slice, Map<String, JsonNode> facts, int offset, Instant now) {
    List<RepoClassifier.Facts> candidates = new ArrayList<>();
    Map<String, JsonNode> byId = new HashMap<>();
    Map<String, Integer> rankById = new HashMap<>();
    for (int i = 0; i < slice.size(); i++) {
      AwesomeListParser.Entry entry = slice.get(i);
      JsonNode repo = facts.get(entry.fullName().toLowerCase(Locale.ROOT));
      if (repo == null
          || repo.path("isArchived").asBoolean(false)
          || repo.path("isFork").asBoolean(false)
          || repo.path("stargazerCount").asLong(0) < limits.minRepoStars()) {
        continue;
      }
      String id = "r" + candidates.size();
      candidates.add(
          new RepoClassifier.Facts(
              id,
              HttpClients.text(repo, "nameWithOwner"),
              HttpClients.text(repo, "description"),
              topics(repo),
              entry.section()));
      byId.put(id, repo);
      rankById.put(id, offset + i);
    }
    Map<String, RepoClassifier.Verdict> verdicts = classify(candidates);
    List<SourceHit> hits = new ArrayList<>();
    for (RepoClassifier.Facts f : candidates) {
      RepoClassifier.Verdict v = verdicts.get(f.id());
      if (v != null && v.ai()) {
        SourceHit hit = toHit(byId.get(f.id()), v.type(), rankById.get(f.id()), now);
        if (hit != null) {
          hits.add(hit);
        }
      }
    }
    hits.sort(Comparator.comparingInt(SourceHit::getRank));
    return hits;
  }

  /**
   * Model first, in batches; anything it did not answer (an error, a missing id) goes to the
   * heuristic.
   */
  private Map<String, RepoClassifier.Verdict> classify(List<RepoClassifier.Facts> candidates) {
    Map<String, RepoClassifier.Verdict> verdicts = new HashMap<>();
    int fallbacks = 0;
    for (int from = 0; from < candidates.size(); from += limits.classifyBatch()) {
      List<RepoClassifier.Facts> batch =
          candidates.subList(from, Math.min(candidates.size(), from + limits.classifyBatch()));
      Map<String, RepoClassifier.Verdict> answered = classifyOrEmpty(batch);
      verdicts.putAll(answered);
      List<RepoClassifier.Facts> missing =
          batch.stream().filter(f -> !answered.containsKey(f.id())).toList();
      if (!missing.isEmpty() && classifier != fallback) {
        fallbacks += missing.size();
        verdicts.putAll(fallback.classify(missing));
      }
    }
    if (fallbacks > 0) {
      log.warn(
          "awesome list classifier {}: {} of {} repositories classified by the heuristic instead",
          classifier.name(),
          fallbacks,
          candidates.size());
    }
    return verdicts;
  }

  private Map<String, RepoClassifier.Verdict> classifyOrEmpty(List<RepoClassifier.Facts> batch) {
    try {
      return classifier.classify(batch);
    } catch (RuntimeException e) {
      log.warn(
          "awesome list classifier {} failed: {}", classifier.name(), e.getClass().getSimpleName());
      return Map.of();
    }
  }

  private static List<String> topics(JsonNode repo) {
    List<String> topics = new ArrayList<>();
    repo.path("repositoryTopics")
        .path("nodes")
        .forEach(n -> {
          String t = HttpClients.text(n.path("topic"), "name");
          if (t != null) {
            topics.add(t);
          }
        });
    return topics;
  }

  private static SourceHit toHit(JsonNode repo, ResourceType type, int rank, Instant now) {
    String name = HttpClients.text(repo, "nameWithOwner");
    String url = HttpClients.text(repo, "url");
    CanonicalUrl canonical = url == null ? null : CanonicalUrl.of(url);
    if (name == null || canonical == null) {
      return null;
    }
    String spdx = HttpClients.text(repo.path("licenseInfo"), "spdxId");
    String license = spdx == null ? null : ResultMerger.normalizeLicense(spdx);
    Set<String> tags = new LinkedHashSet<>(topics(repo));
    Audience audience = type == ResourceType.PRODUCT ? Audience.USE_AI : Audience.BUILD_WITH_AI;
    return new SourceHit(
        ID,
        name,
        URI.create(url),
        canonical,
        null,
        name,
        HttpClients.text(repo, "description"),
        ValueOrigin.SOURCE_PROVIDED,
        type,
        audience,
        null,
        Set.of(),
        tags,
        license,
        null,
        Set.of(),
        Classification.openSource(license),
        DateFact.fromSource(HttpClients.instant(repo, "createdAt"), "github", now),
        DateFact.fromSource(HttpClients.instant(repo, "pushedAt"), "github", now),
        List.of(
            new PopularityMetric(
                PopularityKind.GITHUB_STARS, repo.path("stargazerCount").asLong(), "github", now)),
        now,
        rank);
  }

  private static int offset(String cursor) {
    if (cursor == null) {
      return 0;
    }
    try {
      return Math.max(0, Integer.parseInt(cursor.strip()));
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
