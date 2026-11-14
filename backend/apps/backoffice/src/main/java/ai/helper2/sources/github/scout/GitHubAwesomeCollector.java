package ai.helper2.sources.github.scout;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.Collector;
import ai.helper2.scout.DiscoveryHostStore;
import ai.helper2.scout.ListEntryText;
import ai.helper2.scout.ListText;
import ai.helper2.scout.ScoutCursor;
import ai.helper2.search.PopularityKind;
import ai.helper2.search.PopularityMetric;
import ai.helper2.search.ResultMerger;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceFailure;
import ai.helper2.search.SourceHit;
import ai.helper2.search.Vitality;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.CategoryInput;
import ai.helper2.shared.CategoryMatcher;
import ai.helper2.shared.CollectorIds;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.sources.classifier.RepoClassifier;
import ai.helper2.sources.config.CollectorDescriptors;
import ai.helper2.sources.liveweb.scout.DiscoveryPages;
import ai.helper2.sources.support.Classification;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
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

public class GitHubAwesomeCollector implements Collector {

  static final String ID = CollectorIds.GITHUB_AWESOME;

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
  public CollectedBatch collect(
      @Nullable String checkpoint, @Nullable String resumeCursor, int maxItems) {
    return collect(new ScoutCursor(checkpoint, resumeCursor, null), maxItems);
  }

  @Override
  public CollectedBatch collect(ScoutCursor cursor, int maxItems) {
    Instant startedAt = clock.instant();
    if (!interval.allowsPass(cursor.getCheckpoint(), cursor.getResumeCursor(), startedAt)) {
      return CollectedBatch.notDue();
    }
    DiscoveryPages pages = new DiscoveryPages(hosts.lists());
    Pass pass = read(discoverListRepos());
    List<AwesomeListParser.Entry> all =
        pass.entries().stream().sorted(Comparator.comparing(GitHubAwesomeCollector::key)).toList();
    int offset = firstAfter(all, resumeKey(cursor.getOwnResume()));
    List<SourceHit> listHits = listHits(pass.read(), startedAt);
    boolean reviewHeldLinksBack = cursor.getCandidatesHeldBack();
    List<Candidate> candidates =
        cursor.getOwnResume() == null ? candidates(pass.crawl(), pages) : List.of();
    if (reviewHeldLinksBack || offset >= all.size()) {
      return batch(listHits, null, startedAt, candidates, pass.texts(), List.of());
    }
    int end =
        Math.min(all.size(), offset + Math.max(1, Math.min(maxItems, limits.maxEntriesPerRun())));
    List<AwesomeListParser.Entry> slice = all.subList(offset, end);
    Set<String> notFound = new HashSet<>();
    Map<String, JsonNode> facts = repoFacts(slice, notFound);
    List<SourceHit> hits = new ArrayList<>(toHits(slice, facts, offset, startedAt));
    log.info(
        "awesome lists: entries {}-{} of {}, {} kept as AI resources (classifier {})",
        offset,
        end,
        all.size(),
        hits.size(),
        classifier.name());
    hits.addAll(listHits);
    return batch(
        hits,
        end < all.size() ? RESUME_AFTER + key(all.get(end - 1)) : null,
        startedAt,
        candidates,
        pass.texts(),
        withdrawnUnlessListed(withdrawn(slice, facts, trusted(notFound, slice.size())), hits));
  }

  private CollectedBatch batch(
      List<SourceHit> hits,
      @Nullable String nextCursor,
      Instant startedAt,
      List<Candidate> candidates,
      List<ListText> texts,
      List<String> withdrawn) {
    return new CollectedBatch(
        hits,
        nextCursor,
        startedAt.toString(),
        false,
        candidates,
        null,
        texts,
        false,
        deep.linksPerDay(),
        withdrawn);
  }

  static final int MAX_NOT_FOUND = 50;

  static Set<String> trusted(Set<String> notFound, int entries) {
    if (notFound.size() > MAX_NOT_FOUND || notFound.size() * 10 > Math.max(entries, 10)) {
      log.warn(
          "awesome lists: GitHub did not find {} of {} repositories at once; nothing is withdrawn",
          notFound.size(),
          entries);
      return Set.of();
    }
    return notFound;
  }

  private static List<String> withdrawnUnlessListed(List<String> withdrawn, List<SourceHit> hits) {
    Set<String> listed = new HashSet<>();
    hits.stream()
        .filter(h -> !Boolean.TRUE.equals(h.getVitality().getArchived()))
        .forEach(h -> listed.add(h.getExternalId().toLowerCase(Locale.ROOT)));
    return withdrawn.stream().filter(n -> !listed.contains(n.toLowerCase(Locale.ROOT))).toList();
  }

  static List<String> withdrawn(
      List<AwesomeListParser.Entry> slice, Map<String, JsonNode> facts, Set<String> notFound) {
    Set<String> gone = new LinkedHashSet<>();
    for (AwesomeListParser.Entry entry : slice) {
      String key = key(entry);
      JsonNode repo = facts.get(key);
      if (notFound.contains(key)) {
        gone.add(entry.fullName());
      } else if (repo != null) {
        String name = HttpClients.text(repo, "nameWithOwner");
        if (repo.path("isArchived").asBoolean(false)) {
          gone.add(entry.fullName());
          if (name != null) {
            gone.add(name);
          }
        } else if (name != null && !name.equalsIgnoreCase(entry.fullName())) {
          gone.add(entry.fullName());
        }
      }
    }
    return List.copyOf(gone);
  }

  List<String> discoverLists() {
    return discoverListRepos().stream().map(ListRepo::fullName).toList();
  }

  record ListRepo(String fullName, JsonNode facts) {}

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

  record Pass(
      List<AwesomeListParser.Entry> entries,
      List<ListRepo> read,
      List<ListText> texts,
      List<AwesomeCrawl.Read> crawl) {}

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

  static final String FOUND_IN = "awesome";

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

  static final int README_MAX_BYTES = 5 * 1024 * 1024;

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

  Map<String, JsonNode> repoFacts(List<AwesomeListParser.Entry> entries) {
    return repoFacts(entries, new HashSet<>());
  }

  Map<String, JsonNode> repoFacts(List<AwesomeListParser.Entry> entries, Set<String> notFound) {
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
      JsonNode data = root.path("data");
      for (JsonNode error : root.path("errors")) {
        String type = error.path("type").asString("");
        if ("RATE_LIMITED".equals(type)) {
          throw new SourceFailure.RateLimited(null);
        }
        if ("NOT_FOUND".equals(type)) {
          notFoundAlias(error, data, batch).ifPresent(notFound::add);
        }
      }
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

  static java.util.Optional<String> notFoundAlias(
      JsonNode error, JsonNode data, List<AwesomeListParser.Entry> batch) {
    JsonNode path = error.path("path");
    String alias = path.path(0).asString("");
    if (path.size() != 1 || !alias.matches("r\\d{1,4}") || data.path(alias).isObject()) {
      return java.util.Optional.empty();
    }
    int i = Integer.parseInt(alias.substring(1));
    return i < batch.size() ? java.util.Optional.of(key(batch.get(i))) : java.util.Optional.empty();
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
      if (repo == null || repo.path("stargazerCount").asLong(0) < limits.minRepoStars()) {
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
                    PopularityKind.GITHUB_STARS,
                    repo.path("stargazerCount").asLong(),
                    "github",
                    now)),
            now,
            rank)
        .withVitality(
            new Vitality(
                repo.path("isArchived").asBoolean(false), repo.path("isFork").asBoolean(false)));
  }

  static final String RESUME_AFTER = "after:";

  private static String key(AwesomeListParser.Entry entry) {
    return entry.fullName().toLowerCase(Locale.ROOT);
  }

  private static @Nullable String resumeKey(@Nullable String cursor) {
    return cursor != null && cursor.startsWith(RESUME_AFTER)
        ? cursor.substring(RESUME_AFTER.length())
        : null;
  }

  private static int firstAfter(List<AwesomeListParser.Entry> sorted, @Nullable String after) {
    if (after == null) {
      return 0;
    }
    int i = 0;
    while (i < sorted.size() && key(sorted.get(i)).compareTo(after) <= 0) {
      i++;
    }
    return i;
  }
}
