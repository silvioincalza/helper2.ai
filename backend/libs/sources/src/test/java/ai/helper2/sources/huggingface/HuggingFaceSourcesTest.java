package ai.helper2.sources.huggingface;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import ai.helper2.search.PopularityKind;
import ai.helper2.search.SourceFailure;
import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import ai.helper2.search.VitalityPolicy;
import ai.helper2.shared.Audience;
import ai.helper2.shared.DateStatus;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HuggingFaceSourcesTest {

  final FixtureServer server = new FixtureServer();

  @AfterEach
  void stop() {
    server.close();
  }

  SourceSettings settings() {
    return new SourceSettings(
        true, server.baseUrl(), "", Duration.ofSeconds(2), Duration.ofMinutes(30));
  }

  SourceQuery q(String text) {
    return new SourceQuery(text, List.of(), null, 10);
  }

  SourceQuery publishedAfter(String text, LocalDate day) {
    return new SourceQuery(text, List.of(), null, 10, List.of(text), List.of(), null, null, day);
  }

  @Test
  void huggingFaceModelsMapIdDatesLicenseAndMetrics() {
    server.route("/api/models", FixtureServer.Reply.ok(FixtureServer.fixture("hf-models.json")));
    HuggingFaceModelsSource src =
        new HuggingFaceModelsSource(
            settings(),
            TestHttp.restClientBuilder(
                Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
            SourceFixtures.CLOCK);
    SourceHit h = src.search(q("qwen3")).getFirst();
    assertThat(h.getTitle()).isEqualTo("Qwen/Qwen3-0.6B");
    assertThat(h.getUrl().toString()).isEqualTo("https://huggingface.co/Qwen/Qwen3-0.6B");
    assertThat(h.getType()).isEqualTo(ResourceType.MODEL);
    assertThat(h.getLicense()).isEqualTo("Apache-2.0");
    assertThat(h.getPublished().getValue()).isEqualTo(Instant.parse("2025-04-27T03:40:08Z"));
    assertThat(h.getUpdated().getValue()).isEqualTo(Instant.parse("2025-07-26T03:46:27Z"));
    assertThat(h.getDescription()).as("listing has no description; never invented").isNull();
    assertThat(h.getPopularity())
        .extracting(p -> p.getKind())
        .containsExactlyInAnyOrder(PopularityKind.HF_LIKES, PopularityKind.HF_DOWNLOADS);
    assertThat(h.getTags()).contains("text-generation");
  }

  @Test
  void aModelStatesIfTheHubDisabledItAndTheHubIsAskedForThatFact() {
    server.route(
        "/api/models",
        FixtureServer.Reply.ok(
            """
            [{"id":"acme/open-model","disabled":false},
             {"id":"acme/taken-down-model","disabled":true},
             {"id":"acme/older-answer-model"}]\
            """));
    HuggingFaceModelsSource src =
        new HuggingFaceModelsSource(
            settings(),
            TestHttp.restClientBuilder(
                Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
            SourceFixtures.CLOCK);

    assertThat(src.search(q("model")))
        .extracting(SourceHit::getExternalId, h -> h.getVitality().getDeprecated())
        .containsExactly(
            tuple("acme/open-model", false),
            tuple("acme/taken-down-model", true),
            tuple("acme/older-answer-model", null));
    assertThat(URLDecoder.decode(server.requests.getFirst(), StandardCharsets.UTF_8))
        .contains("expand[]=disabled")
        .contains("expand[]=downloads")
        .contains("expand[]=lastModified")
        .contains("expand[]=tags");
  }

  @Test
  void huggingFaceSpacesAreAppsForBothAudiences() {
    server.route("/api/spaces", FixtureServer.Reply.ok(FixtureServer.fixture("hf-spaces.json")));
    SourceHit h = spaces().search(q("translate")).getFirst();
    assertThat(h.getUrl().toString()).startsWith("https://huggingface.co/spaces/");
    assertThat(h.getType()).isEqualTo(ResourceType.PRODUCT);
    assertThat(h.getAudience()).isEqualTo(Audience.BOTH);
    assertThat(h.getUpdated().getStatus())
        .as("the fixture has no lastModified")
        .isEqualTo(DateStatus.UNKNOWN);
  }

  @Test
  void huggingFace429IsRateLimited() {
    server.route("/api/models", new FixtureServer.Reply(429, "{}", Map.of("Retry-After", "30"), 0));
    HuggingFaceModelsSource src =
        new HuggingFaceModelsSource(
            settings(),
            TestHttp.restClientBuilder(
                Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
            SourceFixtures.CLOCK);
    assertThatThrownBy(() -> src.search(q("x")))
        .isInstanceOfSatisfying(
            SourceFailure.RateLimited.class,
            e -> assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(30)));
  }

  @Test
  void aHubIdThatIsNoValidUrlPathIsSkippedAndTheOtherModelsAreKept() {
    server.route(
        "/api/models",
        FixtureServer.Reply.ok(
            """
            [{"id":"acme/bad id","likes":1},{"id":"acme/good-model","likes":2}]\
            """));
    HuggingFaceModelsSource src =
        new HuggingFaceModelsSource(
            settings(),
            TestHttp.restClientBuilder(
                Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
            SourceFixtures.CLOCK);

    assertThat(src.search(q("model")))
        .extracting(SourceHit::getExternalId)
        .containsExactly("acme/good-model");
  }

  @Test
  void aSpaceStatesIfItRunsNowAndEverySpaceIsReturned() {
    server.route(
        "/api/spaces",
        FixtureServer.Reply.ok(
            """
            [{"id":"acme/running","runtime":{"stage":"RUNNING"}},
             {"id":"acme/running-while-it-builds","runtime":{"stage":"RUNNING_BUILDING"}},
             {"id":"acme/broken","runtime":{"stage":"RUNTIME_ERROR"}},
             {"id":"acme/sleeping","runtime":{"stage":"SLEEPING"}},
             {"id":"acme/paused","runtime":{"stage":"PAUSED"}},
             {"id":"acme/starting","runtime":{"stage":"APP_STARTING"}},
             {"id":"acme/no-runtime"}]\
            """));

    List<SourceHit> hits = spaces().search(q("demo"));

    assertThat(hits)
        .extracting(SourceHit::getExternalId, h -> h.getVitality().getRunning())
        .containsExactly(
            tuple("acme/running", true),
            tuple("acme/running-while-it-builds", true),
            tuple("acme/broken", false),
            tuple("acme/sleeping", false),
            tuple("acme/paused", false),
            tuple("acme/starting", false),
            tuple("acme/no-runtime", false));
  }

  @Test
  void spacesAreFoundByMeaningFirstWithTheSentenceAndItsCategory() {
    server.route(
        "/api/spaces/semantic-search",
        FixtureServer.Reply.ok(
            """
            [{"id":"acme/background-removal","likes":2987,"runtime":{"stage":"RUNNING"}}]\
            """));

    List<SourceHit> hits = spaces().search(q("remove image background"));

    assertThat(hits)
        .extracting(SourceHit::getExternalId)
        .containsExactly("acme/background-removal");
    assertThat(server.requests).hasSize(1);
    assertThat(URLDecoder.decode(server.requests.getFirst(), StandardCharsets.UTF_8))
        .contains("/api/spaces/semantic-search")
        .contains("q=remove image background")
        .contains("category=background-removal");
  }

  @Test
  void aNeedWithNoKnownCategoryIsAskedByItsSentenceAlone() {
    server.route("/api/spaces/semantic-search", FixtureServer.Reply.ok("[]"));
    server.route("/api/spaces", FixtureServer.Reply.ok("[]"));

    spaces().search(q("chat with a pdf"));

    assertThat(URLDecoder.decode(server.requests.getFirst(), StandardCharsets.UTF_8))
        .contains("q=chat with a pdf")
        .doesNotContain("category=");
  }

  @Test
  void whenTheMeaningFindsNothingTheWordsAreSearched() {
    server.route("/api/spaces/semantic-search", FixtureServer.Reply.ok("[]"));
    server.route(
        "/api/spaces",
        FixtureServer.Reply.ok("[{\"id\":\"acme/by-words\",\"runtime\":{\"stage\":\"RUNNING\"}}]"));

    assertThat(spaces().search(q("demo")))
        .extracting(SourceHit::getExternalId)
        .containsExactly("acme/by-words");
  }

  @Test
  void whenNoSpaceFoundByMeaningCanBeAResultTheWordsAreSearched() {
    server.route(
        "/api/spaces/semantic-search",
        FixtureServer.Reply.ok(
            """
            [{"id":"acme/nobody-liked","likes":0,"runtime":{"stage":"RUNNING"}},
             {"id":"acme/asleep","likes":900,"runtime":{"stage":"SLEEPING"}}]\
            """));
    server.route(
        "/api/spaces",
        FixtureServer.Reply.ok(
            "[{\"id\":\"acme/by-words\",\"likes\":40,\"runtime\":{\"stage\":\"RUNNING\"}}]"));

    assertThat(spaces().search(q("demo")))
        .extracting(SourceHit::getExternalId)
        .containsExactly("acme/by-words");
  }

  @Test
  void whenTheSearchByMeaningFailsTheWordsAreSearched() {
    server.route("/api/spaces/semantic-search", new FixtureServer.Reply(404, "{}", Map.of(), 0));
    server.route(
        "/api/spaces",
        FixtureServer.Reply.ok("[{\"id\":\"acme/by-words\",\"runtime\":{\"stage\":\"RUNNING\"}}]"));

    assertThat(spaces().search(q("demo")))
        .extracting(SourceHit::getExternalId)
        .containsExactly("acme/by-words");
  }

  @Test
  void aDateWindowIsNotAskedByMeaning() {
    server.route("/api/spaces", FixtureServer.Reply.ok("[]"));

    spaces().search(publishedAfter("demo", LocalDate.parse("2026-11-01")));

    assertThat(server.requests).hasSize(1);
    assertThat(server.requests.getFirst()).doesNotContain("semantic-search");
  }

  @Test
  void spacesAreAskedMostLikedFirst() {
    server.route("/api/spaces", FixtureServer.Reply.ok("[]"));

    spaces().search(q("demo"));

    assertThat(URLDecoder.decode(server.requests.getLast(), StandardCharsets.UTF_8))
        .contains("sort=likes")
        .contains("direction=-1");
  }

  @Test
  void spacesOfADateWindowAreAskedByTrend() {
    server.route("/api/spaces", FixtureServer.Reply.ok("[]"));

    spaces().search(publishedAfter("demo", LocalDate.parse("2026-11-01")));

    assertThat(URLDecoder.decode(server.requests.getFirst(), StandardCharsets.UTF_8))
        .contains("sort=trendingScore")
        .doesNotContain("sort=likes");
  }

  @Test
  void spacesAskTheHubForTheRuntimeStageInTheSameCall() {
    server.route("/api/spaces", FixtureServer.Reply.ok("[]"));

    spaces().search(q("demo"));

    assertThat(URLDecoder.decode(server.requests.getLast(), StandardCharsets.UTF_8))
        .contains("expand[]=runtime")
        .contains("expand[]=likes")
        .contains("expand[]=lastModified");
  }

  HuggingFaceSpacesSource spaces() {
    return new HuggingFaceSpacesSource(
        settings(),
        TestHttp.restClientBuilder(
            Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
        SourceFixtures.CLOCK,
        new VitalityPolicy());
  }

  @Test
  void aTaskQueryAsksForTheTaskTagMostLikedFirstAndFillsAThinPage() {
    server.route("/api/models", FixtureServer.Reply.ok("[{\"id\":\"BAAI/bge-reranker-v2-m3\"}]"));
    HuggingFaceModelsSource src =
        new HuggingFaceModelsSource(
            settings(),
            TestHttp.restClientBuilder(
                Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
            SourceFixtures.CLOCK);

    List<SourceHit> hits =
        src.search(
            new SourceQuery(
                "reranker model for retrieval",
                List.of(),
                null,
                10,
                List.of("x"),
                List.of(),
                null,
                null,
                null,
                false,
                java.util.Set.of(),
                List.of("reranker", "retrieval")));

    String first = URLDecoder.decode(server.requests.get(0), StandardCharsets.UTF_8);
    String second = URLDecoder.decode(server.requests.get(1), StandardCharsets.UTF_8);
    assertThat(first)
        .contains("pipeline_tag=text-ranking")
        .contains("sort=likes")
        .contains("search=retrieval");
    assertThat(second).contains("pipeline_tag=text-ranking").doesNotContain("search=");
    assertThat(hits)
        .extracting(SourceHit::getExternalId)
        .containsExactly("BAAI/bge-reranker-v2-m3");
  }

  @Test
  void aQueryWithoutTaskWordsSearchesItsWords() {
    server.route("/api/models", FixtureServer.Reply.ok("[]"));
    HuggingFaceModelsSource src =
        new HuggingFaceModelsSource(
            settings(),
            TestHttp.restClientBuilder(
                Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
            SourceFixtures.CLOCK);

    src.search(q("stable diffusion xl"));

    String request = URLDecoder.decode(server.requests.getFirst(), StandardCharsets.UTF_8);
    assertThat(request).contains("search=stable diffusion xl").doesNotContain("&pipeline_tag=");
  }

  @Test
  void aQueryWithoutTaskWordsKeepsTheHubOrderInOneCall() {
    server.route(
        "/api/models", FixtureServer.Reply.ok("[{\"id\":\"acme/one\"},{\"id\":\"acme/two\"}]"));

    List<SourceHit> hits =
        models().search(withTerms("vision language agent", List.of("vision", "language", "agent")));

    assertThat(server.requests).hasSize(1);
    assertThat(URLDecoder.decode(server.requests.getFirst(), StandardCharsets.UTF_8))
        .contains("search=vision language agent")
        .doesNotContain("sort=");
    assertThat(hits).extracting(SourceHit::getExternalId).containsExactly("acme/one", "acme/two");
  }

  @Test
  void aTaskQueryWithoutOtherWordsMakesOneCall() {
    server.route("/api/models", FixtureServer.Reply.ok("[]"));

    models().search(withTerms("image segmentation", List.of("image", "segmentation")));

    assertThat(server.requests).hasSize(1);
    assertThat(URLDecoder.decode(server.requests.getFirst(), StandardCharsets.UTF_8))
        .contains("pipeline_tag=image-segmentation")
        .contains("sort=likes")
        .doesNotContain("search=");
  }

  @Test
  void aTranslatedQueryFindsTheTaskInTheFullTranslation() {
    server.route("/api/models", FixtureServer.Reply.ok("[]"));

    models()
        .search(
            new SourceQuery(
                "modelo de texto a voz",
                List.of(),
                null,
                10,
                List.of("modelo de texto a voz"),
                List.of(),
                "es",
                null,
                null,
                false,
                java.util.Set.of(),
                List.of("text", "speech"),
                "text to speech model"));

    assertThat(server.requests).hasSize(1);
    assertThat(URLDecoder.decode(server.requests.getFirst(), StandardCharsets.UTF_8))
        .contains("pipeline_tag=text-to-speech")
        .contains("sort=likes")
        .doesNotContain("search=");
  }

  HuggingFaceModelsSource models() {
    return new HuggingFaceModelsSource(
        settings(),
        TestHttp.restClientBuilder(
            Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
        SourceFixtures.CLOCK);
  }

  SourceQuery withTerms(String text, List<String> terms) {
    return new SourceQuery(
        text,
        List.of(),
        null,
        10,
        List.of(text),
        List.of(),
        null,
        null,
        null,
        false,
        java.util.Set.of(),
        terms);
  }
}
