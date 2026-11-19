package ai.helper2.sources.github.scout;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.InMemoryListSeedStore;
import ai.helper2.scout.ListKind;
import ai.helper2.scout.ListSeed;
import ai.helper2.scout.ListText;
import ai.helper2.scout.RunStatus;
import ai.helper2.scout.ScoutCursor;
import ai.helper2.scout.SeedDiscoveryHosts;
import ai.helper2.scout.usecase.CappedReview;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.Category;
import ai.helper2.shared.CategoryCatalog;
import ai.helper2.shared.CategoryMatcher;
import ai.helper2.shared.CategoryWords;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubAwesomeDeepSearchTest {

  static final String SEED_README =
      """
      # Awesome LLM
      ## Tools
      - [Interpreter](https://github.com/openinterpreter/openinterpreter) - Runs code.
      - [Acme Voice](https://acme-voice.ai/?utm_source=awesome) - Clones voices.
      ### Vector databases
      - [Vectorly](https://vectorly.io/pricing) - Managed vector search.
      - [Read the docs](https://docs.vectorly.io/start) - How to start.
      - [Raw](https://93.184.216.34/) - An address.
      ## Related lists
      - [RAG list](https://github.com/lists/rag-collection)
      """;

  static final String NESTED_FACTS =
      """
      {"full_name":"lists/rag-collection","html_url":"https://github.com/lists/rag-collection",
       "description":"Curated RAG engines","stargazers_count":800,"archived":false,"fork":false,
       "license":{"spdx_id":"MIT"},"topics":["rag"],"created_at":"2024-01-01T00:00:00Z",
       "pushed_at":"2026-10-01T00:00:00Z"}\
      """;

  static final String NESTED_README =
      """
      # RAG collection
      ## Engines
      - [Ragflow](https://github.com/infiniflow/ragflow) - RAG engine.
      - [Acme Voice again](https://acme-voice.ai) - The same site.
      - [Back](https://github.com/acme/awesome-llm)
      """;

  static final String GRAPHQL =
      """
      {"data":{
        "r0":{"nameWithOwner":"openinterpreter/openinterpreter","url":"https://github.com/openinterpreter/openinterpreter",
              "description":"Code interpreter","stargazerCount":68671,"isArchived":false,"isFork":false,
              "repositoryTopics":{"nodes":[{"topic":{"name":"llm"}}]}},
        "r1":{"nameWithOwner":"infiniflow/ragflow","url":"https://github.com/infiniflow/ragflow",
              "description":"RAG engine","stargazerCount":40000,"isArchived":false,"isFork":false,
              "repositoryTopics":{"nodes":[{"topic":{"name":"rag"}}]}}}}\
      """;

  final FixtureServer server = new FixtureServer();
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  final CategoryMatcher categories =
      new CategoryMatcher(
          new CategoryCatalog(
              List.of(
                  new Category(
                      "rag-and-vector-databases",
                      "RAG and vector databases",
                      "RAG",
                      "builders",
                      CategoryWords.of(List.of("vector database", "vector search"))),
                  new Category(
                      "voice-and-audio",
                      "Voice and audio",
                      "Voice",
                      "creators",
                      CategoryWords.of(List.of("voice clon"))))));

  @BeforeEach
  void routes() {
    server.route("/search/repositories", FixtureServer.Reply.ok(GitHubAwesomeCollectorTest.SEARCH));
    server.route("/repos/acme/awesome-llm/readme", FixtureServer.Reply.ok(SEED_README));
    server.route("/repos/lists/rag-collection/readme", FixtureServer.Reply.ok(NESTED_README));
    server.route("/repos/lists/rag-collection", FixtureServer.Reply.ok(NESTED_FACTS));
    server.route("/graphql", FixtureServer.Reply.ok(GRAPHQL));
  }

  @AfterEach
  void stop() {
    server.close();
  }

  final GitHubAwesomeCollectorTest.FakeModel model = new GitHubAwesomeCollectorTest.FakeModel();
  final InMemoryListSeedStore addedLists = new InMemoryListSeedStore();
  GitHubAwesomeCollector.LinkRules linkRules = GitHubAwesomeCollectorTest.NO_LINK_RULES;
  GitHubAwesomeCollector.Deep deep = GitHubAwesomeCollectorTest.DEEP;

  GitHubAwesomeCollector collector() {
    return new GitHubAwesomeCollector(
        new SourceSettings(true, server.baseUrl(), "t", Duration.ofSeconds(2), Duration.ZERO, null),
        TestHttp.restClientBuilder(
            Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
        SourceFixtures.CLOCK,
        Resilience.NONE,
        new RunInterval(Duration.ofDays(1)),
        List.of("topic:awesome-list topic:llm"),
        new GitHubAwesomeCollector.Limits(10, 1000, 1000, 1000, 50, 25),
        deep,
        model,
        categories,
        meters,
        SeedDiscoveryHosts.store(),
        addedLists,
        SourceFixtures.SHADOW_LIBRARIES,
        linkRules);
  }

  double count(String name, String... tags) {
    var counter = meters.find(name).tags(tags).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void aListAnAdministratorAddedIsReadFirstWhateverItsStarsAndName() {
    addedLists.add(
        new ListSeed(
            ListKind.GITHUB_REPOSITORY,
            "small/llm-links",
            "42",
            SourceFixtures.CLOCK.instant(),
            null));
    server.route(
        "/repos/small/llm-links",
        FixtureServer.Reply.ok(
            """
            {"full_name":"small/llm-links","html_url":"https://github.com/small/llm-links",
             "description":"Links","stargazers_count":3,"archived":false,"fork":false,"topics":[]}\
            """));
    server.route(
        "/repos/small/llm-links/readme",
        FixtureServer.Reply.ok(
            "# Links\n## Engines\n- [Ragflow](https://github.com/infiniflow/ragflow)\n"));

    CollectedBatch batch = collector().collect(null, null, 500);

    assertThat(batch.getLists())
        .extracting(l -> l.getUrl().getValue())
        .startsWith("https://github.com/small/llm-links");
    assertThat(batch.getHits())
        .extracting(SourceHit::getExternalId)
        .contains("small/llm-links", "infiniflow/ragflow");
  }

  @Test
  void aListLinkedFromASeedBecomesAListResourceWithItsSearchText() {
    CollectedBatch batch = collector().collect(null, null, 500);

    assertThat(batch.getHits())
        .filteredOn(h -> h.getTags().contains(GitHubAwesomeCollector.LIST_TAG))
        .extracting(SourceHit::getExternalId, SourceHit::getType)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("acme/awesome-llm", ResourceType.ARTICLE),
            org.assertj.core.groups.Tuple.tuple("lists/rag-collection", ResourceType.ARTICLE));
    assertThat(batch.getLists())
        .extracting(l -> l.getUrl().getValue())
        .containsExactly(
            "https://github.com/acme/awesome-llm", "https://github.com/lists/rag-collection");
    assertThat(count(AwesomeMetrics.LISTS, "depth", "nested")).isEqualTo(1);
  }

  @Test
  void theEntriesOfANestedListAreCollectedButAFollowedListIsNotAnEntry() {
    CollectedBatch batch = collector().collect(null, null, 500);

    assertThat(GitHubAwesomeCollectorTest.entryHits(batch))
        .extracting(SourceHit::getExternalId)
        .containsExactly("openinterpreter/openinterpreter", "infiniflow/ragflow");
    ListText seed = batch.getLists().getFirst();
    assertThat(seed.getEntries())
        .extracting(e -> e.getUrl().getValue())
        .contains("https://github.com/lists/rag-collection");
  }

  @Test
  void aLinkToAProductSiteBecomesACandidateWithItsListAndSection() {
    CollectedBatch batch = collector().collect(null, null, 500);

    assertThat(batch.getCandidates())
        .extracting(c -> c.getUrl().getValue(), Candidate::getTitle, Candidate::getTags)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "https://acme-voice.ai", "Acme Voice", java.util.Set.of()),
            org.assertj.core.groups.Tuple.tuple(
                "https://vectorly.io", "Vectorly", java.util.Set.of("rag-and-vector-databases")));
    Candidate vectorly = batch.getCandidates().get(1);
    assertThat(vectorly.getType()).isEqualTo(ResourceType.PRODUCT);
    assertThat(vectorly.getSnippet()).isNull();
    assertThat(vectorly.getFacts().get(GitHubAwesomeCollector.FOUND_IN))
        .isEqualTo(
            Map.of(
                "list", "https://github.com/acme/awesome-llm",
                "section", "Tools > Vector databases"));
    assertThat(batch.getNewCandidatesPerDay()).isEqualTo(50);
  }

  @Test
  void everySkippedLinkIsCountedByItsReason() {
    collector().collect(null, null, 500);

    assertThat(count(AwesomeMetrics.CANDIDATES)).isEqualTo(2);
    assertThat(count(AwesomeMetrics.SKIPS, "reason", "link-not-a-product-page")).isEqualTo(1);
    assertThat(count(AwesomeMetrics.SKIPS, "reason", "link-ip-address")).isEqualTo(1);
    assertThat(count(AwesomeMetrics.SKIPS, "reason", "link-seen")).isEqualTo(1);
    assertThat(count(AwesomeMetrics.SKIPS, "reason", "nested-seen")).isEqualTo(1);
  }

  @Test
  void candidatesAreProposedOnlyOnTheFirstRunOfAPass() {
    CollectedBatch resumed = collector().collect(null, "1", 500);

    assertThat(resumed.getCandidates()).isEmpty();
  }

  @Test
  void afterARunTheReviewCapHeldBackTheNextRunProposesTheHeldCandidates() {
    List<CappedReview.Run> runs =
        CappedReview.aHeldBackRunThenTheNext(collector(), SourceFixtures.CLOCK);

    assertThat(runs).extracting(CappedReview.Run::getStatus).containsOnly(RunStatus.SUCCEEDED);
    assertThat(runs.get(0).getCursorAfter().getCandidatesHeldBack()).isTrue();
    assertThat(runs.get(0).getProposed()).isNotEmpty();
    assertThat(runs.get(1).getProposed()).containsExactlyElementsOf(runs.get(0).getProposed());
    assertThat(runs.get(1).getCursorAfter().getResumeCursor()).isNull();
  }

  @Test
  void aPassTheReviewCapHeldBackProposesItsLinksAgainAndClassifiesNothingTwice() {
    CollectedBatch first = collector().collect(null, null, 500);
    model.calls.clear();

    CollectedBatch heldBack = collector().collect(ScoutCursor.candidatesHeldBack(null, null), 500);

    assertThat(heldBack.getCandidates())
        .extracting(c -> c.getUrl().getValue())
        .containsExactlyElementsOf(
            first.getCandidates().stream().map(c -> c.getUrl().getValue()).toList());
    assertThat(model.calls).as("the entries were classified on the run before").isEmpty();
    assertThat(GitHubAwesomeCollectorTest.entryHits(heldBack)).isEmpty();
    assertThat(heldBack.getNextCursor()).isNull();
    assertThat(heldBack.getCheckpoint()).isNotNull();
  }

  @Test
  void aCandidateKeepsAtMostTheTagCap() {
    StringBuilder words = new StringBuilder();
    List<Category> many = new java.util.ArrayList<>();
    for (int i = 0; i < 20; i++) {
      many.add(new Category("c" + i, "C" + i, "C" + i, "f", CategoryWords.of(List.of("word" + i))));
      words.append(" word").append(i);
    }
    server.route(
        "/repos/acme/awesome-llm/readme",
        FixtureServer.Reply.ok("# T\n## " + words + "\n- [Many](https://many.ai)\n"));
    GitHubAwesomeCollector c =
        new GitHubAwesomeCollector(
            new SourceSettings(
                true, server.baseUrl(), "t", Duration.ofSeconds(2), Duration.ZERO, null),
            TestHttp.restClientBuilder(
                Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
            SourceFixtures.CLOCK,
            Resilience.NONE,
            new RunInterval(Duration.ofDays(1)),
            List.of("topic:awesome-list topic:llm"),
            new GitHubAwesomeCollector.Limits(10, 1000, 1000, 1000, 50, 25),
            GitHubAwesomeCollectorTest.DEEP,
            new GitHubAwesomeCollectorTest.FakeModel(),
            new CategoryMatcher(new CategoryCatalog(many)),
            meters,
            SeedDiscoveryHosts.store(),
            new InMemoryListSeedStore(),
            SourceFixtures.SHADOW_LIBRARIES,
            linkRules);

    Candidate candidate = c.collect(null, null, 500).getCandidates().getFirst();

    assertThat(candidate.getTags()).hasSize(Candidate.MAX_TAGS).contains("c0", "c11");
  }

  static final String ONE_LINK_OF_EACH_KIND =
      """
      # Awesome LLM
      ## Models
      - [Voice model](https://huggingface.co/acme/voice-model) - Speaks. [Paper](https://arxiv.org/abs/2212.04356)
      - [Tiny](https://hf.co/someone/tiny-experiment) - An experiment.
      ## Courses
      - [Generative AI for everyone](https://www.coursera.org/learn/generative-ai-for-everyone)
      ## Tools
      - [Interpreter](https://github.com/openinterpreter/openinterpreter) - Runs code. [Site](https://interpreter.ai)
      """;

  static final String VOICE_MODEL =
      """
      {"id":"acme/voice-model","likes":120,"downloads":5000,"tags":["license:mit"],
       "pipeline_tag":"text-to-speech","lastModified":"2026-10-01T00:00:00.000Z"}\
      """;

  static final String TINY_MODEL =
      """
      {"id":"someone/tiny-experiment","likes":3,"downloads":10,"tags":[],
       "pipeline_tag":"text-generation"}\
      """;

  void aListWithOneLinkOfEachKind() {
    server.route("/repos/acme/awesome-llm/readme", FixtureServer.Reply.ok(ONE_LINK_OF_EACH_KIND));
    server.route("/api/models/acme/voice-model", FixtureServer.Reply.ok(VOICE_MODEL));
    server.route("/api/models/someone/tiny-experiment", FixtureServer.Reply.ok(TINY_MODEL));
    deep =
        new GitHubAwesomeCollector.Deep(
            2,
            30,
            Duration.ofMinutes(15),
            50_000_000L,
            500,
            100,
            200,
            100,
            50,
            List.of("arxiv.org"));
    linkRules =
        new GitHubAwesomeCollector.LinkRules(
            new ai.helper2.sources.liveweb.scout.CoursePages(List.of("coursera.org=Coursera")),
            new ai.helper2.sources.huggingface.scout.HuggingFaceModelReader(
                TestHttp.restClientBuilder(
                        Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP)
                    .baseUrl(server.baseUrl())
                    .build(),
                Resilience.NONE,
                GitHubAwesomeCollector.POLICY,
                SourceFixtures.CLOCK),
            new ai.helper2.scout.admission.AdmissionPolicy(
                new ai.helper2.search.VitalityPolicy(),
                new ai.helper2.scout.admission.AdmissionRules(
                    Map.of(
                        GitHubAwesomeCollector.ID,
                        new ai.helper2.scout.admission.IndexFloor(0, null, 10)))),
            null,
            null);
  }

  List<String> modelCalls() {
    return server.requests.stream().filter(r -> r.startsWith("/api/models/")).toList();
  }

  static List<SourceHit> models(CollectedBatch batch) {
    return batch.getHits().stream().filter(h -> h.getType() == ResourceType.MODEL).toList();
  }

  @Test
  void everyLinkOfAListEntryGoesToTheRuleOfItsKind() {
    aListWithOneLinkOfEachKind();

    CollectedBatch batch = collector().collect(null, null, 500);

    assertThat(models(batch))
        .extracting(SourceHit::getExternalId, SourceHit::getSourceId)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "https://huggingface.co/acme/voice-model", GitHubAwesomeCollector.ID));
    assertThat(GitHubAwesomeCollectorTest.entryHits(batch))
        .extracting(SourceHit::getExternalId)
        .contains("openinterpreter/openinterpreter");
    assertThat(batch.getCandidates())
        .extracting(c -> c.getUrl().getValue(), Candidate::getType, Candidate::getTitle)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "https://coursera.org/learn/generative-ai-for-everyone",
                ResourceType.COURSE,
                "Generative AI for everyone"),
            org.assertj.core.groups.Tuple.tuple(
                "https://interpreter.ai", ResourceType.PRODUCT, "interpreter.ai"),
            org.assertj.core.groups.Tuple.tuple(
                "https://huggingface.co/someone/tiny-experiment",
                ResourceType.MODEL,
                "someone/tiny-experiment"));
    assertThat(batch.getCandidates())
        .allSatisfy(c ->
            assertThat(c.getFacts().get(GitHubAwesomeCollector.FOUND_IN))
                .isInstanceOfSatisfying(
                    Map.class,
                    found ->
                        assertThat(found.get("list"))
                            .isEqualTo("https://github.com/acme/awesome-llm")));
    assertThat(batch.getCandidates().getFirst().getPlatform()).isEqualTo("Coursera");
    assertThat(count(AwesomeMetrics.SKIPS, "reason", "link-excluded-domain")).isEqualTo(1);
    assertThat(count(AwesomeMetrics.MODELS)).isEqualTo(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"9, false", "10, true"})
  void aListedModelIsIndexedFromTenLikesAndWaitsForAPersonUnderThat(int likes, boolean indexed) {
    aListWithOneLinkOfEachKind();
    server.route(
        "/api/models/someone/tiny-experiment",
        FixtureServer.Reply.ok(
            "{\"id\":\"someone/tiny-experiment\",\"likes\":" + likes + ",\"tags\":[]}"));

    CollectedBatch batch = collector().collect(null, null, 500);

    String tiny = "https://huggingface.co/someone/tiny-experiment";
    assertThat(models(batch).stream().anyMatch(h -> h.getExternalId().equals(tiny)))
        .isEqualTo(indexed);
    assertThat(
            batch.getCandidates().stream()
                .anyMatch(
                    c -> c.getUrl().getValue().equals(tiny) && c.getType() == ResourceType.MODEL))
        .isEqualTo(!indexed);
  }

  @Test
  void aModelOfAListIsAnEntryOfTheListIndexWithItsHeading() {
    aListWithOneLinkOfEachKind();

    ListText list = collector().collect(null, null, 500).getLists().getFirst();

    assertThat(list.getEntries())
        .extracting(e -> e.getUrl().getValue(), e -> e.getHeadingPath(), e -> e.getText())
        .contains(
            org.assertj.core.groups.Tuple.tuple(
                "https://huggingface.co/acme/voice-model", "Models", "Voice model - Speaks. Paper"),
            org.assertj.core.groups.Tuple.tuple(
                "https://huggingface.co/someone/tiny-experiment",
                "Models",
                "Tiny - An experiment."));
  }

  @Test
  void aModelTheHubDoesNotShowIsCountedAndNothingComesOfIt() {
    aListWithOneLinkOfEachKind();
    server.route("/api/models/acme/voice-model", new FixtureServer.Reply(404, "{}", Map.of(), 0));

    CollectedBatch batch = collector().collect(null, null, 500);

    assertThat(models(batch)).isEmpty();
    assertThat(count(AwesomeMetrics.SKIPS, "reason", "model-not-found")).isEqualTo(1);
    assertThat(batch.getCandidates())
        .extracting(c -> c.getUrl().getValue())
        .doesNotContain("https://huggingface.co/acme/voice-model");
  }

  @Test
  void atMostTheCapOfModelsIsReadInAPass() {
    aListWithOneLinkOfEachKind();
    deep =
        new GitHubAwesomeCollector.Deep(
            2, 30, Duration.ofMinutes(15), 50_000_000L, 500, 1, 200, 100, 50, List.of("arxiv.org"));

    collector().collect(null, null, 500);

    assertThat(modelCalls()).hasSize(1);
    assertThat(count(AwesomeMetrics.SKIPS, "reason", "model-cap")).isEqualTo(1);
  }

  @Test
  void whenTheHubFailsTheOtherModelsWaitForTheNextPassAndTheRestOfThePassIsKept() {
    aListWithOneLinkOfEachKind();
    server.route("/api/models/acme/voice-model", new FixtureServer.Reply(500, "", Map.of(), 0));

    CollectedBatch batch = collector().collect(null, null, 500);

    assertThat(modelCalls()).hasSize(1);
    assertThat(count(AwesomeMetrics.SKIPS, "reason", "model-unavailable")).isEqualTo(2);
    assertThat(GitHubAwesomeCollectorTest.entryHits(batch))
        .extracting(SourceHit::getExternalId)
        .containsExactly("openinterpreter/openinterpreter");
    assertThat(batch.getCandidates())
        .extracting(Candidate::getType)
        .containsExactly(ResourceType.COURSE, ResourceType.PRODUCT);
  }

  @Test
  void modelsAreReadOnlyOnTheFirstRunOfAPassButStayEntriesOfTheListIndex() {
    aListWithOneLinkOfEachKind();

    CollectedBatch resumed = collector().collect(null, "1", 500);

    assertThat(modelCalls()).isEmpty();
    assertThat(models(resumed)).isEmpty();
    assertThat(resumed.getLists().getFirst().getEntries())
        .extracting(e -> e.getUrl().getValue())
        .contains("https://huggingface.co/acme/voice-model");
  }

  static final String PAPERS_AND_PACKAGES =
      """
      # Awesome LLM
      ## Papers
      - [Attention](https://arxiv.org/abs/1706.03762v5) - Transformers. [[pdf]](https://arxiv.org/pdf/1706.03762.pdf)
      - [Unknown](https://arxiv.org/abs/9999.99999)
      ## SDKs
      - [LangChain core](https://www.npmjs.com/package/@langchain/core) - Abstractions.
      - [Agent kit](https://www.npmjs.com/package/llm-agent-kit) - Agents.
      - [Old client](https://www.npmjs.com/package/old-llm-client)
      """;

  boolean arxivSlotFree = true;

  void aListWithPapersAndPackages(int maxPapers, int maxPackages) {
    server.route("/repos/acme/awesome-llm/readme", FixtureServer.Reply.ok(PAPERS_AND_PACKAGES));
    server.route("/graphql", FixtureServer.Reply.ok("{\"data\":{}}"));
    server.route(
        "/api/query",
        FixtureServer.Reply.ok(
            ai.helper2.sources.arxiv.scout.ArxivPaperReaderTest.feed(
                ai.helper2.sources.arxiv.scout.ArxivPaperReaderTest.entry(
                    "1706.03762", "Attention Is All You Need", "cs.CL"))));
    server.route(
        "/@langchain/core/latest",
        FixtureServer.Reply.ok(
            """
            {"name":"@langchain/core","description":"Core LangChain.js abstractions",
             "keywords":["llm","ai"],"license":"MIT",
             "repository":{"url":"git+ssh://git@github.com/langchain-ai/langchainjs.git"}}\
            """));
    server.route(
        "/llm-agent-kit/latest",
        FixtureServer.Reply.ok(
            """
            {"name":"llm-agent-kit","description":"Agents for LLM apps","keywords":["llm","ai"],
             "repository":"https://github.com/someone/misc"}\
            """));
    server.route(
        "/old-llm-client/latest",
        FixtureServer.Reply.ok(
            """
            {"name":"old-llm-client","description":"An LLM client","keywords":["llm","ai"],
             "deprecated":"Use another one"}\
            """));
    deep =
        new GitHubAwesomeCollector.Deep(
            2,
            30,
            Duration.ofMinutes(15),
            50_000_000L,
            500,
            100,
            maxPapers,
            maxPackages,
            50,
            List.of("arxiv.org", "npmjs.com"));
    org.springframework.web.client.RestClient http =
        TestHttp.restClientBuilder(
                Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP)
            .baseUrl(server.baseUrl())
            .build();
    linkRules =
        new GitHubAwesomeCollector.LinkRules(
            new ai.helper2.sources.liveweb.scout.CoursePages(List.of()),
            null,
            new ai.helper2.scout.admission.AdmissionPolicy(),
            new ai.helper2.sources.arxiv.scout.ArxivPaperReader(
                http,
                Resilience.NONE,
                GitHubAwesomeCollector.POLICY,
                SourceFixtures.CLOCK,
                (host, interval) -> arxivSlotFree,
                pause -> {}),
            new ai.helper2.sources.npm.scout.NpmPackageReader(
                http, Resilience.NONE, GitHubAwesomeCollector.POLICY, SourceFixtures.CLOCK));
  }

  List<String> calls(String prefix) {
    return server.requests.stream().filter(r -> r.startsWith(prefix)).toList();
  }

  static List<SourceHit> packages(CollectedBatch batch) {
    return batch.getHits().stream()
        .filter(h -> h.getCanonicalUrl().getHost().equals("npmjs.com"))
        .toList();
  }

  static List<SourceHit> ofType(CollectedBatch batch, ResourceType type) {
    return batch.getHits().stream().filter(h -> h.getType() == type).toList();
  }

  @Test
  void aPaperLinkedTwiceIsAskedOnceByItsIdAndIsOnePaper() {
    aListWithPapersAndPackages(200, 100);

    CollectedBatch batch = collector().collect(null, null, 500);

    assertThat(calls("/api/query")).hasSize(1);
    assertThat(calls("/api/query").getFirst()).contains("id_list=1706.03762,9999.99999");
    assertThat(ofType(batch, ResourceType.PAPER))
        .extracting(SourceHit::getExternalId, SourceHit::getSourceId)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "https://arxiv.org/abs/1706.03762", GitHubAwesomeCollector.ID));
    assertThat(batch.getLists().getFirst().getEntries())
        .extracting(e -> e.getUrl().getValue(), e -> e.getHeadingPath())
        .contains(
            org.assertj.core.groups.Tuple.tuple("https://arxiv.org/abs/1706.03762", "Papers"));
    assertThat(batch.getCandidates()).isEmpty();
    assertThat(count(AwesomeMetrics.PAPERS)).isEqualTo(1);
    assertThat(count(AwesomeMetrics.SKIPS, "reason", "paper-not-kept")).isEqualTo(1);
  }

  @Test
  void aPackageIsReadByItsNameAndItsListEntryNamesTheResourceItJoins() {
    aListWithPapersAndPackages(200, 100);

    CollectedBatch batch = collector().collect(null, null, 500);

    assertThat(server.requests)
        .contains("/@langchain/core/latest", "/llm-agent-kit/latest", "/old-llm-client/latest");
    assertThat(packages(batch))
        .extracting(SourceHit::getTitle, h -> h.getVitality().getDeprecated())
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("@langchain/core", false),
            org.assertj.core.groups.Tuple.tuple("llm-agent-kit", false),
            org.assertj.core.groups.Tuple.tuple("old-llm-client", true));
    assertThat(batch.getLists().getFirst().getEntries())
        .extracting(e -> e.getUrl().getValue(), e -> e.getHeadingPath())
        .contains(
            org.assertj.core.groups.Tuple.tuple(
                "https://github.com/langchain-ai/langchainjs", "SDKs"),
            org.assertj.core.groups.Tuple.tuple("https://npmjs.com/package/llm-agent-kit", "SDKs"));
    assertThat(count(AwesomeMetrics.PACKAGES)).isEqualTo(3);
  }

  @Test
  void atMostTheCapsOfPapersAndPackagesAreReadInAPass() {
    aListWithPapersAndPackages(1, 1);

    collector().collect(null, null, 500);

    assertThat(calls("/api/query").getFirst())
        .contains("id_list=1706.03762")
        .doesNotContain("9999.99999");
    assertThat(count(AwesomeMetrics.SKIPS, "reason", "paper-cap")).isEqualTo(1);
    assertThat(server.requests.stream().filter(r -> r.endsWith("/latest"))).hasSize(1);
    assertThat(count(AwesomeMetrics.SKIPS, "reason", "package-cap")).isEqualTo(2);
  }

  @Test
  void whenArxivsSharedSlotStaysTakenThePapersWaitAndThePackagesAreStillRead() {
    aListWithPapersAndPackages(200, 100);
    arxivSlotFree = false;

    CollectedBatch batch = collector().collect(null, null, 500);

    assertThat(calls("/api/query")).isEmpty();
    assertThat(count(AwesomeMetrics.SKIPS, "reason", "paper-rate-limited")).isEqualTo(2);
    assertThat(packages(batch)).hasSize(3);
  }

  @Test
  void whenTheRegistryFailsTheOtherPackagesWaitForTheNextPass() {
    aListWithPapersAndPackages(200, 100);
    server.route("/@langchain/core/latest", new FixtureServer.Reply(500, "", Map.of(), 0));

    CollectedBatch batch = collector().collect(null, null, 500);

    assertThat(server.requests.stream().filter(r -> r.endsWith("/latest"))).hasSize(1);
    assertThat(count(AwesomeMetrics.SKIPS, "reason", "package-unavailable")).isEqualTo(3);
    assertThat(ofType(batch, ResourceType.PAPER)).hasSize(1);
  }

  @Test
  void papersAndPackagesAreReadOnlyOnTheFirstRunOfAPass() {
    aListWithPapersAndPackages(200, 100);

    collector().collect(null, "1", 500);

    assertThat(calls("/api/query")).isEmpty();
    assertThat(server.requests.stream().filter(r -> r.endsWith("/latest"))).isEmpty();
  }
}
