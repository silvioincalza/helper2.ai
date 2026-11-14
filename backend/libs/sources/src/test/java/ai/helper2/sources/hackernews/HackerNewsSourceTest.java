package ai.helper2.sources.hackernews;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
import io.micrometer.observation.ObservationRegistry;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HackerNewsSourceTest {

  final FixtureServer server = new FixtureServer();

  @AfterEach
  void stop() {
    server.close();
  }

  HackerNewsSource source() {
    return new HackerNewsSource(
        new SourceSettings(
            true, server.baseUrl(), "", Duration.ofSeconds(2), Duration.ofMinutes(30)),
        TestHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP),
        SourceFixtures.CLOCK);
  }

  static final String STORIES =
      """
      {"hits":[
        {"objectID":"45112233","title":"GPT-6 Astra: A new generation of intelligence",
         "url":"https://openai.com/index/gpt-6-astra/","points":2279,
         "created_at":"2026-09-03T17:01:22.000Z"},
        {"objectID":"45112234","title":"Ask HN: What do you use for LLM evals?",
         "url":null,"points":120,"created_at":"2026-09-04T08:00:00.000Z"},
        {"objectID":"45112235","title":"A new way to bake sourdough bread",
         "url":"https://example.com/bread","points":800,"created_at":"2026-09-05T08:00:00.000Z"},
        {"objectID":"45112236","title":"Ollama runs Gemma locally",
         "url":"https://ollama.com/blog/gemma","points":300,"created_at":"2026-09-06T08:00:00.000Z"}
      ]}
      """;

  @Test
  void aStoryIsTheLinkedPageAsNewsWithItsDate() {
    server.route("/api/v1/search", FixtureServer.Reply.ok(STORIES));

    SourceHit h = source().search(new SourceQuery("gpt-6 astra", List.of(), null, 10)).getFirst();

    assertThat(h.getSourceId()).isEqualTo("hacker-news");
    assertThat(h.getExternalId()).isEqualTo("45112233");
    assertThat(h.getUrl().toString()).isEqualTo("https://openai.com/index/gpt-6-astra/");
    assertThat(h.getTitle()).isEqualTo("GPT-6 Astra: A new generation of intelligence");
    assertThat(h.getType()).isEqualTo(ResourceType.NEWS);
    assertThat(h.getPublished().getValue()).isEqualTo(Instant.parse("2026-09-03T17:01:22Z"));
    assertThat(h.getRank()).isZero();
  }

  @Test
  void storiesWithoutALinkOrNotAboutAiAreLeftOutAndRanksStayConsecutive() {
    server.route("/api/v1/search", FixtureServer.Reply.ok(STORIES));

    List<SourceHit> hits = source().search(new SourceQuery("ai", List.of(), null, 10));

    assertThat(hits).extracting(SourceHit::getExternalId).containsExactly("45112233", "45112236");
    assertThat(hits).extracting(SourceHit::getRank).containsExactly(0, 1);
  }

  @Test
  void asksForStoriesWithTheContentWords() {
    server.route("/api/v1/search", FixtureServer.Reply.ok("{\"hits\":[]}"));

    source()
        .search(
            new SourceQuery(
                "openai new model launch",
                List.of(),
                null,
                10,
                List.of("x"),
                List.of(),
                null,
                null,
                null,
                false,
                Set.of(),
                List.of("openai", "model", "launch")));

    String request = URLDecoder.decode(server.requests.getFirst(), StandardCharsets.UTF_8);
    assertThat(request)
        .contains("query=openai model launch")
        .contains("tags=story")
        .contains("hitsPerPage=30")
        .doesNotContain("numericFilters");
  }

  static final String ANNOUNCEMENTS =
      """
      {"hits":[
        {"objectID":"1","title":"DeepSeek-V4: towards cheaper reasoning",
         "url":"https://api-docs.deepseek.com/news/v4","created_at":"2026-09-03T17:01:22.000Z"},
        {"objectID":"2","title":"Qwen3 is out","url":"https://qwenlm.github.io/blog/qwen3/",
         "created_at":"2026-09-03T17:01:22.000Z"},
        {"objectID":"3","title":"Llama3.3 70B released","url":"https://ai.meta.com/blog/llama/",
         "created_at":"2026-09-03T17:01:22.000Z"},
        {"objectID":"4","title":"Gemma 3 on your laptop","url":"https://blog.google/gemma-3/",
         "created_at":"2026-09-03T17:01:22.000Z"}
      ]}
      """;

  @Test
  void announcementsNamedByTheirModelAreKept() {
    server.route("/api/v1/search", FixtureServer.Reply.ok(ANNOUNCEMENTS));

    List<SourceHit> hits = source().search(new SourceQuery("new model", List.of(), null, 10));

    assertThat(hits).extracting(SourceHit::getExternalId).containsExactly("1", "2", "3", "4");
  }

  @Test
  void returnsAtMostTheLimitAfterTheFilter() {
    server.route("/api/v1/search", FixtureServer.Reply.ok(ANNOUNCEMENTS));

    List<SourceHit> hits = source().search(new SourceQuery("new model", List.of(), null, 2));

    assertThat(hits).extracting(SourceHit::getExternalId).containsExactly("1", "2");
    assertThat(URLDecoder.decode(server.requests.getFirst(), StandardCharsets.UTF_8))
        .contains("hitsPerPage=6");
  }

  @Test
  void aDateBoundAsksOnlyForStoriesFromThatDay() {
    server.route("/api/v1/search", FixtureServer.Reply.ok("{\"hits\":[]}"));

    source()
        .search(
            new SourceQuery(
                "openai launch",
                List.of(),
                null,
                10,
                List.of("openai launch"),
                List.of(),
                null,
                null,
                LocalDate.parse("2026-11-02")));

    assertThat(URLDecoder.decode(server.requests.getFirst(), StandardCharsets.UTF_8))
        .contains("numericFilters=created_at_i>=1793577600");
    assertThat(source().getDescriptor().getFiltersByDate()).isTrue();
  }

  @Test
  void aBlankQueryMakesNoCall() {
    assertThat(source().search(new SourceQuery(" ", List.of(), null, 10))).isEmpty();
    assertThat(server.requests).isEmpty();
  }
}
