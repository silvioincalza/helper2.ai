package ai.helper2.sources.github;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.ListText;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Deep search in awesome lists (ADR 0058): nested lists, product links, section categories. */
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

  GitHubAwesomeCollector collector() {
    return new GitHubAwesomeCollector(
        new SourceSettings(true, server.baseUrl(), "t", Duration.ofSeconds(2), Duration.ZERO),
        ObservedHttp.restClientBuilder(
            Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
        SourceFixtures.CLOCK,
        Resilience.NONE,
        new RunInterval(Duration.ofDays(1)),
        List.of("topic:awesome-list topic:llm"),
        new GitHubAwesomeCollector.Limits(10, 1000, 1000, 1000, 50, 25),
        GitHubAwesomeCollectorTest.DEEP,
        new GitHubAwesomeCollectorTest.FakeModel(),
        categories,
        meters);
  }

  double count(String name, String... tags) {
    var counter = meters.find(name).tags(tags).counter();
    return counter == null ? 0 : counter.count();
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
    // The nested list links back to the seed: the cycle is not followed.
    assertThat(count(AwesomeMetrics.SKIPS, "reason", "nested-seen")).isEqualTo(1);
  }

  @Test
  void candidatesAreProposedOnlyOnTheFirstRunOfAPass() {
    CollectedBatch resumed = collector().collect(null, "1", 500);

    assertThat(resumed.getCandidates()).isEmpty();
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
            new SourceSettings(true, server.baseUrl(), "t", Duration.ofSeconds(2), Duration.ZERO),
            ObservedHttp.restClientBuilder(
                Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
            SourceFixtures.CLOCK,
            Resilience.NONE,
            new RunInterval(Duration.ofDays(1)),
            List.of("topic:awesome-list topic:llm"),
            new GitHubAwesomeCollector.Limits(10, 1000, 1000, 1000, 50, 25),
            GitHubAwesomeCollectorTest.DEEP,
            new GitHubAwesomeCollectorTest.FakeModel(),
            new CategoryMatcher(new CategoryCatalog(many)),
            meters);

    Candidate candidate = c.collect(null, null, 500).getCandidates().getFirst();

    assertThat(candidate.getTags()).hasSize(Candidate.MAX_TAGS).contains("c0", "c11");
  }
}
