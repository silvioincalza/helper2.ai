package ai.helper2.sources.github.scout;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CandidateSignals;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.Collector;
import ai.helper2.scout.ScoutCursor;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceFailure;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.config.CollectorDescriptors;
import ai.helper2.sources.depsdev.PackageFinder;
import ai.helper2.sources.depsdev.RegistryPackage;
import ai.helper2.sources.github.scout.SdkSearchPlan.Query;
import ai.helper2.sources.support.Classification;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import io.micrometer.core.instrument.Metrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public class GitHubSdkCollector implements Collector {

  public static final String POLICY = "collector-" + CollectorDescriptors.GITHUB_SDKS;

  public static final String PACKAGES_POLICY = "collector-deps-dev";

  static final String UNREAD = "helper2.scout.sdks.unread";

  private static final int MAX_REMEMBERED_REPOS = 5000;

  private static final int SEARCH_RESULT_CAP = 1000;

  public interface ReviewRoom {
    boolean known(CanonicalUrl url);

    int roomToday();
  }

  @FunctionalInterface
  public interface Pause {
    void sleep(Duration duration);

    Pause NONE = d -> {};

    static Pause sleeping() {
      return d -> {
        try {
          Thread.sleep(d);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      };
    }
  }

  public record Limits(
      SdkSearchPlan plan,
      Set<String> officialOrgs,
      int perQuery,
      int pagesPerQuery,
      int queriesPerRun,
      int rateLimitReserve,
      Duration searchPause,
      int perDay) {

    public Limits {
      officialOrgs =
          officialOrgs.stream()
              .map(o -> o.toLowerCase(Locale.ROOT))
              .collect(Collectors.toUnmodifiableSet());
    }
  }

  public record Ports(PackageFinder packages, ReviewRoom review, Pause pause) {}

  private final RestClient http;
  private final Clock clock;
  private final Resilience resilience;
  private final RunInterval interval;
  private final Limits limits;
  private final Ports ports;
  private final SourceDescriptor descriptor;
  private final Map<String, Remembered> packagesByRepo = new ConcurrentHashMap<>();

  private record Remembered(List<RegistryPackage> packages, Instant at) {}

  public GitHubSdkCollector(
      SourceSettings settings,
      RestClient.Builder gitHub,
      Clock clock,
      Resilience resilience,
      RunInterval interval,
      Limits limits,
      Ports ports,
      SourceDescriptor descriptor) {
    if (settings.enabled()) {
      resilience.require(POLICY);
      resilience.require(PACKAGES_POLICY);
    }
    this.http = HttpClients.gitHubClient(gitHub, settings);
    this.clock = clock;
    this.resilience = resilience;
    this.interval = interval;
    this.limits = limits;
    this.ports = ports;
    this.descriptor = descriptor;
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
    if (cursor.getResumeCursor() == null) {
      packagesByRepo.clear();
    }
    Searched searched = search(startOf(cursor.getOwnResume()));
    List<Candidate> candidates = propose(searched.repos());
    boolean complete = searched.next() >= limits.plan().size();
    return new CollectedBatch(
        List.of(),
        complete ? null : Integer.toString(searched.next()),
        complete ? startedAt.toString() : null,
        false,
        candidates,
        null,
        List.of(),
        false,
        limits.perDay());
  }

  static int startOf(@Nullable String ownResume) {
    if (ownResume == null) {
      return 0;
    }
    try {
      return Math.max(0, Integer.parseInt(ownResume));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private record Searched(Map<CanonicalUrl, SdkRepo> repos, int next) {}

  private record Page(JsonNode body, @Nullable Integer remaining) {}

  private Searched search(int from) {
    Map<CanonicalUrl, SdkRepo> repos = new LinkedHashMap<>();
    List<Query> queries = limits.plan().slice(from, limits.queriesPerRun());
    RateBudget budget = new RateBudget(limits.rateLimitReserve());
    int next = from;
    for (Query query : queries) {
      if (budget.low()) {
        break;
      }
      searchAllPages(query, repos, budget);
      next++;
    }
    return new Searched(repos, next);
  }

  private void searchAllPages(Query query, Map<CanonicalUrl, SdkRepo> repos, RateBudget budget) {
    int read = 0;
    long total = 0;
    for (int page = 1; page <= limits.pagesPerQuery(); page++) {
      Page answer = page(query, page, budget);
      budget.seen(answer.remaining());
      JsonNode items = answer.body().path("items");
      total = Math.min(answer.body().path("total_count").asLong(0), SEARCH_RESULT_CAP);
      if (answer.body().path("incomplete_results").asBoolean(false)) {
        Metrics.counter(UNREAD, "reason", "incomplete").increment();
      }
      items.forEach(item -> add(item, query, repos));
      read += items.size();
      if (items.size() < limits.perQuery() || read >= total || budget.low()) {
        break;
      }
    }
    if (total > read) {
      Metrics.counter(UNREAD, "reason", "beyond-pages").increment(total - read);
    }
  }

  private void add(JsonNode item, Query query, Map<CanonicalUrl, SdkRepo> repos) {
    SdkRepo found = SdkRepo.of(item, isOfficial(item));
    if (found != null && found.isAi()) {
      repos.computeIfAbsent(found.url(), u -> found).foundBy(query);
    }
  }

  private boolean isOfficial(JsonNode item) {
    String owner = HttpClients.text(item.path("owner"), "login");
    return owner != null && limits.officialOrgs().contains(owner.toLowerCase(Locale.ROOT));
  }

  private Page page(Query query, int page, RateBudget budget) {
    if (budget.searched()) {
      ports.pause().sleep(limits.searchPause());
    }
    AtomicReference<HttpHeaders> headers = new AtomicReference<>(HttpHeaders.EMPTY);
    JsonNode body =
        resilience.call(
            POLICY,
            () ->
                HttpClients.getJson(
                    () -> {
                      ResponseEntity<String> response =
                          http.get()
                              .uri(u ->
                                  u.path("/search/repositories")
                                      .queryParam("q", query.text())
                                      .queryParam("sort", "stars")
                                      .queryParam("order", "desc")
                                      .queryParam("per_page", limits.perQuery())
                                      .queryParam("page", page)
                                      .build())
                              .retrieve()
                              .toEntity(String.class);
                      headers.set(response.getHeaders());
                      return response.getBody();
                    },
                    clock));
    return new Page(body, remaining(headers.get()));
  }

  private static @Nullable Integer remaining(HttpHeaders headers) {
    try {
      String value = headers.getFirst("x-ratelimit-remaining");
      return value == null ? null : Integer.valueOf(value.strip());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static final class RateBudget {
    private final int reserve;
    private boolean searched;
    private boolean low;

    RateBudget(int reserve) {
      this.reserve = reserve;
    }

    void seen(@Nullable Integer remaining) {
      searched = true;
      if (remaining != null && remaining <= reserve) {
        low = true;
      }
    }

    boolean searched() {
      return searched;
    }

    boolean low() {
      return low;
    }
  }

  private List<Candidate> propose(Map<CanonicalUrl, SdkRepo> repos) {
    List<SdkRepo> fresh =
        repos.values().stream()
            .filter(r -> !ports.review().known(r.url()))
            .sorted(Comparator.comparingInt(r -> r.official() ? 0 : 1))
            .toList();
    int room = Math.max(0, ports.review().roomToday());
    if (fresh.size() > room) {
      Metrics.counter(UNREAD, "reason", "over-daily-cap").increment(fresh.size() - room);
    }
    return fresh.stream()
        .limit(room)
        .map(repo -> new Found(repo, packagesOf(repo)))
        .sorted(Comparator.comparingInt(Found::queueRank))
        .map(Found::toCandidate)
        .toList();
  }

  private record Found(SdkRepo repo, List<RegistryPackage> packages) {

    int queueRank() {
      return repo.official() && !packages.isEmpty() ? 0 : 1;
    }

    Candidate toCandidate() {
      return repo.toCandidate(packages);
    }
  }

  private List<RegistryPackage> packagesOf(SdkRepo repo) {
    Instant now = clock.instant();
    Remembered known = packagesByRepo.get(repo.name());
    if (known != null && now.isBefore(known.at().plus(cacheFor()))) {
      return known.packages();
    }
    try {
      List<RegistryPackage> found = ports.packages().packagesOf(repo.name(), repo.language());
      if (packagesByRepo.size() < MAX_REMEMBERED_REPOS || known != null) {
        packagesByRepo.put(repo.name(), new Remembered(found, now));
      }
      return found;
    } catch (SourceFailure e) {
      HttpClients.dropped(CollectorDescriptors.GITHUB_SDKS, "packages-unavailable");
      return List.of();
    }
  }

  private Duration cacheFor() {
    return interval.every().isZero() ? Duration.ofDays(1) : interval.every();
  }

  record SdkRepo(
      CanonicalUrl url,
      String name,
      @Nullable String description,
      @Nullable String language,
      CandidateSignals signals,
      boolean official,
      Set<String> labels,
      Set<String> topics,
      Set<String> languages) {

    static @Nullable SdkRepo of(JsonNode item, boolean official) {
      String name = HttpClients.text(item, "full_name");
      CanonicalUrl url = CanonicalUrl.of(HttpClients.text(item, "html_url"));
      if (name == null || url == null) {
        return null;
      }
      Set<String> labels = new LinkedHashSet<>();
      item.path("topics").forEach(t -> labels.add(t.asString()));
      String language = HttpClients.text(item, "language");
      Set<String> languages = new LinkedHashSet<>();
      if (language != null) {
        languages.add(language);
      }
      return new SdkRepo(
          url,
          name,
          HttpClients.text(item, "description"),
          language,
          RepositorySignals.of(item),
          official,
          labels,
          new LinkedHashSet<>(),
          languages);
    }

    boolean isAi() {
      return Classification.isAiRelated(name, description, labels);
    }

    void foundBy(Query query) {
      if (query.language() != null) {
        languages.add(query.language());
      }
      String topic = topicOf(query.text());
      if (topic != null) {
        topics.add(topic);
      }
    }

    Candidate toCandidate(List<RegistryPackage> packages) {
      Map<String, Object> facts = new LinkedHashMap<>();
      facts.put("topics", List.copyOf(topics));
      facts.put(SdkFacts.KEY, SdkFacts.of(name, List.copyOf(languages), official, packages));
      return new Candidate(
          url,
          name,
          description,
          null,
          Classification.typeOf(name, description, labels, ResourceType.LIBRARY),
          tags(),
          facts,
          signals);
    }

    private Set<String> tags() {
      Set<String> tags = new LinkedHashSet<>();
      languages.stream().map(SdkFacts::languageTag).forEach(tags::add);
      tags.addAll(topics);
      return tags.stream()
          .limit(Candidate.MAX_TAGS)
          .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static @Nullable String topicOf(String query) {
      if (!query.startsWith("topic:")) {
        return null;
      }
      int end = query.indexOf(' ');
      return query.substring("topic:".length(), end < 0 ? query.length() : end);
    }
  }
}
