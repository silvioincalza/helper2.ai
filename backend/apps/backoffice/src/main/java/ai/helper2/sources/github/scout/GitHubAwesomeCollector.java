package ai.helper2.sources.github.scout;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.Collector;
import ai.helper2.scout.DiscoveryHostStore;
import ai.helper2.scout.ListEntryText;
import ai.helper2.scout.ListKind;
import ai.helper2.scout.ListSeed;
import ai.helper2.scout.ListSeedStore;
import ai.helper2.scout.ListText;
import ai.helper2.scout.ScoutCursor;
import ai.helper2.scout.admission.Admission;
import ai.helper2.scout.admission.AdmissionPolicy;
import ai.helper2.search.PopularityKind;
import ai.helper2.search.PopularityMetric;
import ai.helper2.search.RepositoryJoin;
import ai.helper2.search.ResultMerger;
import ai.helper2.search.ShadowLibraries;
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
import ai.helper2.shared.WebPageTitle;
import ai.helper2.sources.arxiv.scout.ArxivPaperReader;
import ai.helper2.sources.classifier.RepoClassifier;
import ai.helper2.sources.config.CollectorDescriptors;
import ai.helper2.sources.huggingface.scout.HuggingFaceModelReader;
import ai.helper2.sources.liveweb.scout.CoursePages;
import ai.helper2.sources.liveweb.scout.DiscoveryPages;
import ai.helper2.sources.npm.scout.NpmPackageReader;
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
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.MissingNode;
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
      int maxModelsPerPass,
      int maxPapersPerPass,
      int maxPackagesPerPass,
      int linksPerDay,
      List<String> excludeDomains) {

    public Deep {
      excludeDomains = List.copyOf(excludeDomains);
    }
  }

  public record LinkRules(
      CoursePages courses,
      @Nullable HuggingFaceModelReader models,
      AdmissionPolicy modelAdmission,
      @Nullable ArxivPaperReader papers,
      @Nullable NpmPackageReader packages) {}

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
  private final ListEntryLinks entryLinks;
  private final LinkRules linkRules;
  private final CategoryMatcher categories;
  private final DiscoveryHostStore hosts;
  private final ListSeedStore addedLists;
  private final AwesomeMetrics metrics;
  private final ListReader readmes;
  private final WebPageLists webPages;

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
      DiscoveryHostStore hosts,
      ListSeedStore addedLists,
      ShadowLibraries shadowLibraries,
      LinkRules linkRules) {
    this(
        settings,
        builder,
        clock,
        resilience,
        interval,
        topicQueries,
        limits,
        deep,
        classifier,
        categories,
        meters,
        hosts,
        addedLists,
        shadowLibraries,
        linkRules,
        WebPageLists.notRead(addedLists, clock));
  }

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
      DiscoveryHostStore hosts,
      ListSeedStore addedLists,
      ShadowLibraries shadowLibraries,
      LinkRules linkRules,
      WebPageLists webPages) {
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
    this.linkRules = linkRules;
    this.entryLinks =
        new ListEntryLinks(
            new ProductLinks(deep.excludeDomains(), shadowLibraries),
            linkRules.courses(),
            new ListEntryLinks.Readers(
                linkRules.models() != null && deep.maxModelsPerPass() > 0,
                linkRules.papers() != null && deep.maxPapersPerPass() > 0,
                linkRules.packages() != null && deep.maxPackagesPerPass() > 0));
    this.categories = categories;
    this.hosts = hosts;
    this.addedLists = addedLists;
    this.metrics = new AwesomeMetrics(meters);
    this.http = HttpClients.gitHubClient(builder, settings);
    this.readmes = new GitHubReadmeReader(http, resilience, clock);
    this.webPages = webPages;
    this.descriptor = CollectorDescriptors.gitHubAwesome(settings);
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
    Pass pass = read(addedLists(), discoverListRepos());
    List<AwesomeListParser.Entry> all =
        pass.entries().stream().sorted(Comparator.comparing(GitHubAwesomeCollector::key)).toList();
    int offset = firstAfter(all, resumeKey(cursor.getOwnResume()));
    List<SourceHit> listHits = listHits(pass.crawl(), startedAt);
    boolean reviewHeldLinksBack = cursor.getCandidatesHeldBack();
    boolean firstRunOfThePass = cursor.getOwnResume() == null;
    if (reviewHeldLinksBack || offset >= all.size()) {
      RoutedLinks links = firstRunOfThePass ? links(pass, pages, startedAt) : RoutedLinks.NONE;
      return batch(
          joined(listHits, links.confirmed()),
          null,
          startedAt,
          links.candidates(),
          searchTexts(pass.crawl(), links.listedAs()),
          List.of());
    }
    int end =
        Math.min(all.size(), offset + Math.max(1, Math.min(maxItems, limits.maxEntriesPerRun())));
    List<AwesomeListParser.Entry> slice = all.subList(offset, end);
    Set<String> notFound = new HashSet<>();
    Map<String, JsonNode> facts = repoFacts(slice, notFound);
    List<SourceHit> hits =
        new ArrayList<>(toHits(slice, facts, offset, startedAt, pass.readmeBudget()));
    log.info(
        "awesome lists: entries {}-{} of {}, {} kept as AI resources (classifier {})",
        offset,
        end,
        all.size(),
        hits.size(),
        classifier.name());
    hits.addAll(listHits);
    List<String> withdrawn =
        withdrawnUnlessListed(withdrawn(slice, facts, trusted(notFound, slice.size())), hits);
    RoutedLinks links = firstRunOfThePass ? links(pass, pages, startedAt) : RoutedLinks.NONE;
    return batch(
        joined(hits, links.confirmed()),
        end < all.size() ? RESUME_AFTER + key(all.get(end - 1)) : null,
        startedAt,
        links.candidates(),
        searchTexts(pass.crawl(), links.listedAs()),
        withdrawn);
  }

  private static List<SourceHit> joined(List<SourceHit> hits, List<SourceHit> confirmed) {
    List<SourceHit> all = new ArrayList<>(hits);
    all.addAll(confirmed);
    return all;
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

  record WebPage(String address, String title) {}

  record ListRepo(String fullName, JsonNode facts, @Nullable WebPage page) {

    ListRepo(String fullName, JsonNode facts) {
      this(fullName, facts, null);
    }

    static ListRepo webPage(CanonicalUrl url, String address, String title) {
      return new ListRepo(url.getValue(), MissingNode.getInstance(), new WebPage(address, title));
    }

    @Nullable CanonicalUrl url() {
      return CanonicalUrl.of(page == null ? GITHUB + fullName : fullName);
    }
  }

  private List<String> addedLists() {
    return addedLists.all().stream()
        .filter(seed -> seed.getKind() == ListKind.GITHUB_REPOSITORY)
        .map(ListSeed::getAddress)
        .toList();
  }

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
      List<AwesomeCrawl.Read> crawl,
      ReadmeBudget readmeBudget) {}

  static final int MAX_LIST_TEXT_BYTES = 256 * 1024;

  static final String LIST_TAG = "awesome-list";

  private static final String GITHUB = "https://github.com/";

  private Pass read(List<String> added, List<ListRepo> seeds) {
    AwesomeCrawl.Limits crawlLimits = crawlLimits();
    ReadmeBudget budget = AwesomeCrawl.budget(crawlLimits, clock);
    List<AwesomeCrawl.Read> crawl =
        AwesomeCrawl.crawl(
            added,
            webPages.read(budget, metrics),
            seeds,
            gitHubLists(),
            crawlLimits,
            budget,
            metrics);
    Set<String> lists = new HashSet<>();
    crawl.forEach(r -> lists.add(r.list().fullName().toLowerCase(Locale.ROOT)));
    Map<String, AwesomeListParser.Entry> unique = new LinkedHashMap<>();
    for (AwesomeCrawl.Read r : crawl) {
      for (AwesomeListParser.Entry e : r.parsed().entries()) {
        String key = e.fullName().toLowerCase(Locale.ROOT);
        if (unique.size() < limits.maxEntriesPerPass() && !lists.contains(key)) {
          unique.putIfAbsent(key, e);
        }
      }
    }
    List<ListRepo> read = crawl.stream().map(AwesomeCrawl.Read::list).toList();
    return new Pass(List.copyOf(unique.values()), read, crawl, budget);
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

  record RoutedLinks(
      List<Candidate> candidates, List<SourceHit> confirmed, Map<String, CanonicalUrl> listedAs) {

    static final RoutedLinks NONE = new RoutedLinks(List.of(), List.of(), Map.of());
  }

  private record ModelLink(
      ListEntryLinks.Model model, AwesomeListParser.Link link, CanonicalUrl list) {}

  private RoutedLinks links(Pass pass, DiscoveryPages pages, Instant now) {
    Map<String, Candidate> found = new LinkedHashMap<>();
    Map<String, ModelLink> models = new LinkedHashMap<>();
    Map<String, String> papers = new LinkedHashMap<>();
    Map<String, String> packages = new LinkedHashMap<>();
    for (AwesomeCrawl.Read r : pass.crawl()) {
      CanonicalUrl list = r.list().url();
      if (list == null) {
        continue;
      }
      for (AwesomeListParser.Link link : r.parsed().links()) {
        switch (entryLinks.route(link, pages)) {
          case ListEntryLinks.Skipped skipped -> metrics.skipped("link-" + skipped.reason());
          case ListEntryLinks.Model model ->
              models.putIfAbsent(model.page().getValue(), new ModelLink(model, link, list));
          case ListEntryLinks.Paper paper ->
              papers.putIfAbsent(paper.page().getValue(), paper.id());
          case ListEntryLinks.Package linked ->
              packages.putIfAbsent(linked.page().getValue(), linked.name());
          case ListEntryLinks.Course course -> propose(found, courseCandidate(course, link, list));
          case ListEntryLinks.Product product ->
              propose(found, candidate(product.site(), link, list, ResourceType.PRODUCT));
        }
      }
    }
    Map<String, CanonicalUrl> listedAs = new HashMap<>();
    List<SourceHit> hits =
        new ArrayList<>(
            readModels(List.copyOf(models.values()), found, listedAs, now, pass.readmeBudget()));
    hits.addAll(readPapers(List.copyOf(papers.values()), now, pass.readmeBudget()));
    hits.addAll(readPackages(packages, listedAs, now, pass.readmeBudget()));
    metrics.proposed(found.size());
    return new RoutedLinks(List.copyOf(found.values()), hits, listedAs);
  }

  private List<SourceHit> readPapers(List<String> linked, Instant now, ReadmeBudget budget) {
    List<String> ids = linked.subList(0, Math.min(linked.size(), deep.maxPapersPerPass()));
    metrics.skipped("paper-cap", linked.size() - ids.size());
    List<SourceHit> hits = new ArrayList<>();
    for (int from = 0; from < ids.size(); from += ArxivPaperReader.BATCH) {
      int left = ids.size() - from;
      String spent = budget.spent();
      if (spent != null) {
        metrics.skipped(spent, left);
        return hits;
      }
      List<String> batch = ids.subList(from, Math.min(ids.size(), from + ArxivPaperReader.BATCH));
      List<SourceHit> read;
      try {
        read = linkRules.papers().read(batch, ID, now);
      } catch (SourceFailure.RateLimited e) {
        log.warn("awesome lists: arXiv slot not free, {} papers left for the next pass", left);
        metrics.skipped("paper-rate-limited", left);
        return hits;
      } catch (RuntimeException e) {
        log.warn(
            "awesome lists: arXiv unavailable ({}), {} papers left for the next pass",
            e.getClass().getSimpleName(),
            left);
        metrics.skipped("paper-unavailable", left);
        return hits;
      }
      hits.addAll(read);
      metrics.papersRead(read.size());
      metrics.skipped("paper-not-kept", batch.size() - read.size());
    }
    return hits;
  }

  private List<SourceHit> readPackages(
      Map<String, String> nameByPage,
      Map<String, CanonicalUrl> listedAs,
      Instant now,
      ReadmeBudget budget) {
    List<SourceHit> hits = new ArrayList<>();
    int read = 0;
    for (Map.Entry<String, String> linked : nameByPage.entrySet()) {
      int left = nameByPage.size() - read;
      String spent = read >= deep.maxPackagesPerPass() ? "package-cap" : budget.spent();
      if (spent != null) {
        metrics.skipped(spent, left);
        return hits;
      }
      NpmPackageReader.Answer answer;
      try {
        answer = linkRules.packages().read(linked.getValue(), ID, now);
      } catch (SourceFailure.RateLimited e) {
        log.warn("awesome lists: npm rate limit, {} packages left for the next pass", left);
        metrics.skipped("package-rate-limited", left);
        return hits;
      } catch (RuntimeException e) {
        log.warn(
            "awesome lists: npm unavailable ({}), {} packages left for the next pass",
            e.getClass().getSimpleName(),
            left);
        metrics.skipped("package-unavailable", left);
        return hits;
      }
      read++;
      SourceHit found = answer.aiPackage();
      if (found == null) {
        metrics.skipped(answer.found() ? "package-not-ai" : "package-not-found");
      } else {
        hits.add(found);
        CanonicalUrl repository = RepositoryJoin.of(found);
        listedAs.put(linked.getKey(), repository == null ? found.getCanonicalUrl() : repository);
        metrics.packageRead();
      }
    }
    return hits;
  }

  private void propose(Map<String, Candidate> found, Candidate candidate) {
    String url = candidate.getUrl().getValue();
    if (found.containsKey(url)) {
      metrics.skipped("link-seen");
    } else if (found.size() >= deep.maxLinksPerPass()) {
      metrics.skipped("link-cap");
    } else {
      found.put(url, candidate);
    }
  }

  private List<SourceHit> readModels(
      List<ModelLink> links,
      Map<String, Candidate> found,
      Map<String, CanonicalUrl> listedAs,
      Instant now,
      ReadmeBudget budget) {
    List<SourceHit> hits = new ArrayList<>();
    for (int i = 0; i < links.size(); i++) {
      int left = links.size() - i;
      String spent = i >= deep.maxModelsPerPass() ? "model-cap" : budget.spent();
      if (spent != null) {
        metrics.skipped(spent, left);
        return hits;
      }
      ModelLink link = links.get(i);
      Optional<SourceHit> model;
      try {
        model = linkRules.models().read(link.model().id(), ID, now);
      } catch (SourceFailure.RateLimited e) {
        log.warn("awesome lists: Hugging Face rate limit, {} models left for the next pass", left);
        metrics.skipped("model-rate-limited", left);
        return hits;
      } catch (RuntimeException e) {
        log.warn(
            "awesome lists: Hugging Face unavailable ({}), {} models left for the next pass",
            e.getClass().getSimpleName(),
            left);
        metrics.skipped("model-unavailable", left);
        return hits;
      }
      if (model.isEmpty()) {
        metrics.skipped("model-not-found");
      } else if (indexedWithoutAPerson(model.get())) {
        hits.add(model.get());
        listedAs.put(link.model().page().getValue(), model.get().getCanonicalUrl());
        metrics.modelRead();
      } else {
        propose(
            found,
            candidate(model.get().getCanonicalUrl(), link.link(), link.list(), ResourceType.MODEL));
      }
    }
    return hits;
  }

  private boolean indexedWithoutAPerson(SourceHit model) {
    return !(linkRules.modelAdmission().decide(model, ID) instanceof Admission.Review);
  }

  private Candidate courseCandidate(
      ListEntryLinks.Course course, AwesomeListParser.Link link, CanonicalUrl list) {
    String title =
        WebPageTitle.of(ListEntryLinks.title(link, course.page()), course.page().getHost())
            .getTitle();
    return new Candidate(
        course.page(),
        title,
        null,
        course.platform(),
        ResourceType.COURSE,
        Set.of(),
        Map.of(FOUND_IN, foundIn(link, list)));
  }

  private static Map<String, Object> foundIn(AwesomeListParser.Link link, CanonicalUrl list) {
    Map<String, Object> awesome = new LinkedHashMap<>();
    awesome.put("list", list.getValue());
    awesome.put("section", link.headingPath());
    return awesome;
  }

  private Candidate candidate(
      CanonicalUrl site, AwesomeListParser.Link link, CanonicalUrl list, ResourceType type) {
    String title =
        type == ResourceType.MODEL ? site.getHuggingFaceModel() : ListEntryLinks.title(link, site);
    Set<String> tags =
        new LinkedHashSet<>(
            categories.match(
                new CategoryInput(
                    title, null, List.of(), null, List.of(), List.of(link.headingPath()))));
    Set<String> capped = new LinkedHashSet<>(tags.stream().limit(Candidate.MAX_TAGS).toList());
    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put(FOUND_IN, foundIn(link, list));
    facts.put("categories", List.copyOf(capped));
    return new Candidate(
        site,
        title,
        null,
        capped.isEmpty() ? null : String.join(", ", capped),
        type,
        capped,
        facts);
  }

  static final String FOUND_IN = "awesome";

  private List<ListText> searchTexts(
      List<AwesomeCrawl.Read> crawl, Map<String, CanonicalUrl> listedAs) {
    List<ListText> texts = new ArrayList<>();
    for (AwesomeCrawl.Read r : crawl) {
      CanonicalUrl url = r.list().url();
      if (url != null) {
        texts.add(
            new ListText(url, r.parsed().headingsText(), searchEntries(r.parsed(), listedAs)));
      }
    }
    return texts;
  }

  private List<ListEntryText> searchEntries(
      AwesomeListParser.Parsed parsed, Map<String, CanonicalUrl> listedAs) {
    AwesomeListParser.SearchBudget budget =
        new AwesomeListParser.SearchBudget(limits.maxEntriesPerPass(), MAX_LIST_TEXT_BYTES);
    List<ListEntryText> entries = new ArrayList<>();
    for (AwesomeListParser.Entry e : parsed.searchEntries(budget)) {
      CanonicalUrl entryUrl = CanonicalUrl.of(GITHUB + e.fullName());
      if (entryUrl != null) {
        entries.add(new ListEntryText(entryUrl, e.text(), e.headingPath()));
      }
    }
    for (AwesomeListParser.Link link : parsed.links()) {
      CanonicalUrl linked = entryLinks.confirmablePage(link);
      if (linked != null && budget.keeps(link.entryText(), link.headingPath())) {
        CanonicalUrl resource = listedAs.getOrDefault(linked.getValue(), linked);
        entries.add(new ListEntryText(resource, link.entryText(), link.headingPath()));
      }
    }
    return entries;
  }

  private static List<SourceHit> listHits(List<AwesomeCrawl.Read> lists, Instant now) {
    List<SourceHit> hits = new ArrayList<>();
    for (AwesomeCrawl.Read read : lists) {
      SourceHit hit =
          read.list().page() == null
              ? listHit(read.list(), typeOfRepository(read), now)
              : pageHit(read.list(), read.list().page(), typeOfWebPage(read), now);
      if (hit != null) {
        hits.add(hit);
      }
    }
    return hits;
  }

  private static ResourceType typeOfRepository(AwesomeCrawl.Read read) {
    return AwesomeLists.isAwesomeList(
            read.list().fullName(), restTopics(read.list().facts()), read.parsed())
        ? ResourceType.AWESOME_LIST
        : ResourceType.ARTICLE;
  }

  private static ResourceType typeOfWebPage(AwesomeCrawl.Read read) {
    return AwesomeLists.readsAsList(read.parsed())
        ? ResourceType.AWESOME_LIST
        : ResourceType.ARTICLE;
  }

  private static @Nullable SourceHit pageHit(
      ListRepo list, WebPage page, ResourceType type, Instant now) {
    CanonicalUrl canonical = list.url();
    if (canonical == null) {
      return null;
    }
    return new SourceHit(
            ID,
            canonical.getValue(),
            URI.create(page.address()),
            canonical,
            page.title(),
            type,
            now)
        .withAudience(Audience.BOTH)
        .withTags(Set.of(LIST_TAG));
  }

  private static List<String> restTopics(@Nullable JsonNode repo) {
    List<String> topics = new ArrayList<>();
    if (repo != null) {
      repo.path("topics")
          .forEach(t -> {
            if (t.isString()) {
              topics.add(t.asString());
            }
          });
    }
    return topics;
  }

  private static SourceHit listHit(ListRepo list, ResourceType type, Instant now) {
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
            list.fullName(),
            type,
            now)
        .withDescription(HttpClients.text(repo, "description"))
        .withAudience(Audience.BOTH)
        .withTags(tags)
        .withLicense(license, Classification.openSource(license))
        .withPublished(DateFact.fromSource(HttpClients.instant(repo, "created_at"), "github", now))
        .withUpdated(DateFact.fromSource(HttpClients.instant(repo, "pushed_at"), "github", now))
        .withPopularity(
            List.of(
                new PopularityMetric(
                    PopularityKind.GITHUB_STARS,
                    repo.path("stargazers_count").asLong(),
                    "github",
                    now)));
  }

  static final int README_MAX_BYTES = GitHubReadmeReader.MAX_BYTES;

  static final Duration README_DEADLINE = GitHubReadmeReader.DEADLINE;

  private String readme(String list) {
    return readmes.markdown(list);
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
      List<AwesomeListParser.Entry> slice,
      Map<String, JsonNode> facts,
      int offset,
      Instant now,
      ReadmeBudget budget) {
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
        ResourceType type = typeOf(f, v, budget);
        SourceHit hit =
            type == null ? null : toHit(byId.get(f.id()), type, rankById.get(f.id()), now);
        if (hit != null) {
          hits.add(hit);
        }
      }
    }
    hits.sort(Comparator.comparingInt(SourceHit::getRank));
    return hits;
  }

  private @Nullable ResourceType typeOf(
      RepoClassifier.Facts repo, RepoClassifier.Verdict verdict, ReadmeBudget budget) {
    if (verdict.type() != ResourceType.AWESOME_LIST) {
      return verdict.type();
    }
    Optional<Boolean> list = readsAsAwesomeList(repo, budget);
    if (list.isEmpty()) {
      return null;
    }
    return list.get() ? ResourceType.AWESOME_LIST : typeWithoutTheList(repo);
  }

  private ResourceType typeWithoutTheList(RepoClassifier.Facts repo) {
    return fallback.classify(List.of(repo)).get(repo.id()).type();
  }

  private Optional<Boolean> readsAsAwesomeList(RepoClassifier.Facts repo, ReadmeBudget budget) {
    String fullName = repo.fullName();
    if (fullName == null || !AwesomeLists.named(fullName, repo.topics())) {
      return Optional.of(false);
    }
    String spent = budget.spent();
    if (spent != null) {
      log.info("awesome lists: README of {} left for the next run ({})", fullName, spent);
      metrics.skipped(spent);
      return Optional.empty();
    }
    try {
      String readme = readme(fullName);
      budget.read(readme);
      return Optional.of(AwesomeLists.isAwesomeList(fullName, repo.topics(), readme));
    } catch (SourceFailure.RateLimited e) {
      log.warn("awesome lists: rate limited while reading the README of {}", fullName);
      throw e;
    } catch (SourceFailure.Unavailable e) {
      if (noReadme(e)) {
        return Optional.of(false);
      }
      log.info(
          "awesome lists: README of {} unavailable ({}), left for the next run",
          fullName,
          e.getMessage());
      return Optional.empty();
    } catch (RuntimeException e) {
      log.info(
          "awesome lists: README of {} unavailable ({}), left for the next run",
          fullName,
          e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  private static boolean noReadme(SourceFailure.Unavailable e) {
    return !e.getTransient() && e.getMessage() != null && e.getMessage().startsWith("HTTP 4");
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
    return new SourceHit(ID, name, URI.create(url), canonical, name, type, now)
        .withDescription(HttpClients.text(repo, "description"))
        .withAudience(audience)
        .withTags(tags)
        .withLicense(license, Classification.openSource(license))
        .withPublished(DateFact.fromSource(HttpClients.instant(repo, "createdAt"), "github", now))
        .withUpdated(DateFact.fromSource(HttpClients.instant(repo, "pushedAt"), "github", now))
        .withPopularity(
            List.of(
                new PopularityMetric(
                    PopularityKind.GITHUB_STARS,
                    repo.path("stargazerCount").asLong(),
                    "github",
                    now)))
        .withRank(rank)
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
