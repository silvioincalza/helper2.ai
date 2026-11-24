package ai.searchavo.sources.arxiv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.searchavo.platform.resilience.OutboundThrottle;
import ai.searchavo.search.SourceFailure;
import ai.searchavo.search.SourceHit;
import ai.searchavo.search.SourceQuery;
import ai.searchavo.shared.Audience;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.DateFact;
import ai.searchavo.shared.DatePrecision;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.shared.ValueOrigin;
import ai.searchavo.sources.SourceFixtures;
import ai.searchavo.sources.support.SourceSettings;
import ai.searchavo.testing.FixtureServer;
import ai.searchavo.testing.TestHttp;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ArxivSourceTest {

  final FixtureServer server = new FixtureServer();

  @AfterEach
  void stop() {
    server.close();
  }

  final List<Duration> asked = new ArrayList<>();

  boolean slotFree = true;
  final OutboundThrottle throttle =
      (host, interval) -> {
        assertThat(host).isEqualTo("arxiv");
        asked.add(interval);
        return slotFree;
      };

  ArxivSource source() {
    return new ArxivSource(
        new SourceSettings(
            true, server.baseUrl(), "", Duration.ofSeconds(2), Duration.ofHours(6), null),
        TestHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP),
        SourceFixtures.CLOCK,
        throttle);
  }

  @Test
  void everyCallTakesTheSharedThreeSecondSlotFirst() {
    server.route(
        "/api/query", FixtureServer.Reply.ok("<feed xmlns=\"http://www.w3.org/2005/Atom\"/>"));

    source().search(query("agents"));

    assertThat(asked).containsExactly(Duration.ofSeconds(3));
    assertThat(server.requests).hasSize(1);
  }

  @Test
  void whenAnotherReplicaHoldsTheSlotOrRedisIsDownTheCallIsRateLimitedWithoutReachingArxiv() {
    slotFree = false;

    assertThatThrownBy(() -> source().search(query("agents")))
        .isInstanceOfSatisfying(
            SourceFailure.RateLimited.class,
            e -> assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(3)));
    assertThat(server.requests).isEmpty();
  }

  static SourceQuery query(String text) {
    return new SourceQuery(text, List.of(), null, 10);
  }

  @Test
  void mapsTheRecordedAtomFeedToPapers() {
    server.route("/api/query", FixtureServer.Reply.ok(FixtureServer.fixture("arxiv-search.xml")));

    List<SourceHit> hits = source().search(query("retrieval augmented generation"));

    assertThat(hits)
        .extracting(SourceHit::getExternalId)
        .containsExactly("https://arxiv.org/abs/2411.18583", "https://arxiv.org/abs/2502.00306");
    SourceHit first = hits.getFirst();
    assertThat(first)
        .isEqualTo(
            new SourceHit(
                "arxiv",
                "https://arxiv.org/abs/2411.18583",
                URI.create("https://arxiv.org/abs/2411.18583"),
                CanonicalUrl.of("https://arxiv.org/abs/2411.18583"),
                null,
                "Automated Literature Review Using NLP Techniques and LLM-Based Retrieval-Augmented Generation",
                "This research presents and compares multiple approaches to automate the generation of literature reviews using several Natural Language Processing (NLP) techniques and retrieval-augmented generation (RAG) with a Large Language Model (LLM). The ever-increasing number of research articles provides a huge challenge for manual literature review. It has resulted in an increased demand for automation. Developing a system capable of automatically generating the literature reviews from only the PDF files as input is the primary objective of this research work. The effectiveness of several Natural L…",
                ValueOrigin.SOURCE_PROVIDED,
                ResourceType.PAPER,
                Audience.BUILD_WITH_AI,
                null,
                Set.of(),
                Set.of("cs.AI", "cs.CL", "cs.IR", "cs.LG"),
                null,
                null,
                Set.of(),
                null,
                new DateFact(
                    Instant.parse("2024-11-27T18:27:07Z"),
                    DatePrecision.INSTANT,
                    "arxiv",
                    List.of(),
                    false),
                new DateFact(
                    Instant.parse("2024-11-27T18:27:07Z"),
                    DatePrecision.INSTANT,
                    "arxiv",
                    List.of(),
                    false),
                List.of(),
                SourceFixtures.CLOCK.instant()));
    assertThat(first.getSourceId()).isEqualTo("arxiv");
    assertThat(first.getUrl())
        .as("version suffix dropped, https")
        .isEqualTo(URI.create("https://arxiv.org/abs/2411.18583"));
    assertThat(first.getTitle())
        .isEqualTo(
            "Automated Literature Review Using NLP Techniques and LLM-Based Retrieval-Augmented Generation");
    assertThat(first.getType()).isEqualTo(ResourceType.PAPER);
    assertThat(first.getAudience()).isEqualTo(Audience.BUILD_WITH_AI);
    assertThat(first.getTags()).containsExactly("cs.CL", "cs.AI", "cs.IR", "cs.LG");
    assertThat(first.getDescription())
        .startsWith("This research presents")
        .hasSizeLessThanOrEqualTo(600);
    assertThat(first.getPublished().getValue()).isEqualTo(Instant.parse("2024-11-27T18:27:07Z"));
    assertThat(hits.get(1).getUpdated().getValue())
        .isEqualTo(Instant.parse("2025-06-30T16:37:59Z"));
    assertThat(hits).extracting(SourceHit::getRank).containsExactly(0, 1);
  }

  @Test
  void aFeedWithADoctypeIsUnavailableAndNoEntityIsResolved() {
    server.route("/secret", FixtureServer.Reply.ok("leaked"));
    server.route(
        "/api/query",
        FixtureServer.Reply.ok(
            """
            <?xml version="1.0"?>
            <!DOCTYPE feed [<!ENTITY xxe SYSTEM "%s/secret">]>
            <feed xmlns="http://www.w3.org/2005/Atom"><entry><id>http://arxiv.org/abs/1</id><title>&xxe;</title></entry></feed>
            """
                .formatted(server.baseUrl())));

    assertThatThrownBy(() -> source().search(query("agents")))
        .isInstanceOf(SourceFailure.Unavailable.class)
        .hasMessage("malformed response");
    assertThat(server.requests).noneMatch(r -> r.startsWith("/secret"));
  }

  @Test
  void searchWordsAreCleanedOfQuerySyntaxAndLimitedToAiCategories() {
    server.route(
        "/api/query", FixtureServer.Reply.ok("<feed xmlns=\"http://www.w3.org/2005/Atom\"/>"));

    source().search(query("rag: \"agents\" (OR) cat:hep-th"));

    String sent = URLDecoder.decode(server.requests.getFirst(), StandardCharsets.UTF_8);
    assertThat(sent)
        .contains("search_query=all:(rag agents OR cat hep-th) AND " + ArxivSource.AI_CATEGORIES)
        .contains("sortBy=relevance")
        .contains("max_results=10");
  }

  @Test
  void browsingListsNewestSubmissionsInAiCategoriesInsideTheWindow() {
    SourceQuery browse =
        new SourceQuery(
            "",
            List.of(),
            null,
            50,
            List.of(""),
            List.of(),
            null,
            null,
            LocalDate.parse("2026-10-01"),
            true);

    assertThat(ArxivSource.searchQuery(browse))
        .isEqualTo(ArxivSource.AI_CATEGORIES + " AND submittedDate:[202610010000 TO 299912312359]");
  }
}
