package ai.helper2.sources.arxiv.scout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.resilience.OutboundThrottle;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.search.SourceFailure;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class ArxivPaperReaderTest {

  public static String entry(String id, String title, String category) {
    return """
    <entry>
      <id>http://arxiv.org/abs/%sv2</id>
      <title>%s</title>
      <updated>2025-06-30T16:37:59Z</updated>
      <summary>  A summary.</summary>
      <category term="%s" scheme="http://arxiv.org/schemas/atom"/>
      <published>2024-11-27T18:27:07Z</published>
      <author><name>Ada Example</name></author>
    </entry>
    """
        .formatted(id, title, category);
  }

  public static String feed(String... entries) {
    return "<?xml version='1.0' encoding='UTF-8'?><feed xmlns=\"http://www.w3.org/2005/Atom\">"
        + String.join("", entries)
        + "</feed>";
  }

  final FixtureServer server = new FixtureServer();
  final List<Duration> pauses = new ArrayList<>();
  int slotFreeAfter;
  final List<String> slots = new ArrayList<>();
  final OutboundThrottle throttle =
      (host, interval) -> {
        slots.add(host + " " + interval);
        return slots.size() > slotFreeAfter;
      };

  @AfterEach
  void stop() {
    server.close();
  }

  ArxivPaperReader reader() {
    return new ArxivPaperReader(
        TestHttp.restClientBuilder(
                Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP)
            .baseUrl(server.baseUrl())
            .build(),
        Resilience.NONE,
        "collector-github-awesome",
        SourceFixtures.CLOCK,
        throttle,
        pauses::add);
  }

  List<SourceHit> read(String... ids) {
    return reader().read(List.of(ids), "github-awesome", SourceFixtures.CLOCK.instant());
  }

  @Test
  void papersAreAskedByIdInOneCallAndOnePaperIsOneAddressWhateverItsVersion() {
    server.route(
        "/api/query",
        FixtureServer.Reply.ok(
            feed(
                entry("1706.03762", "Attention Is All You Need", "cs.CL"),
                entry("cs/0112017", "Old style", "cs.AI"))));

    List<SourceHit> papers = read("1706.03762", "cs/0112017", "9999.99999");

    assertThat(server.requests).hasSize(1);
    assertThat(server.requests.getFirst())
        .startsWith("/api/query?")
        .contains("id_list=1706.03762,cs/0112017,9999.99999")
        .contains("max_results=3")
        .doesNotContain("search_query");
    assertThat(papers)
        .extracting(SourceHit::getExternalId, SourceHit::getType, SourceHit::getSourceId)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "https://arxiv.org/abs/1706.03762", ResourceType.PAPER, "github-awesome"),
            org.assertj.core.groups.Tuple.tuple(
                "https://arxiv.org/abs/cs/0112017", ResourceType.PAPER, "github-awesome"));
    assertThat(papers.getFirst().getTitle()).isEqualTo("Attention Is All You Need");
    assertThat(papers.getFirst().getDescription()).isEqualTo("A summary.");
  }

  @Test
  void noAuthorIsKept() {
    server.route(
        "/api/query", FixtureServer.Reply.ok(feed(entry("1706.03762", "Attention", "cs.CL"))));

    assertThat(read("1706.03762").getFirst().toString()).doesNotContain("Ada Example");
  }

  @Test
  void aPaperOutsideTheAiCategoriesOfTheArxivSourceIsNotKept() {
    server.route(
        "/api/query",
        FixtureServer.Reply.ok(
            feed(
                entry("2401.00001", "Galaxies", "astro-ph.GA"),
                entry("2401.00002", "Agents", "cs.MA"))));

    assertThat(read("2401.00001", "2401.00002"))
        .extracting(SourceHit::getTitle)
        .containsExactly("Agents");
  }

  @Test
  void everyCallTakesTheSlotAllMachinesShareAndWaitsForIt() {
    slotFreeAfter = 2;
    server.route("/api/query", FixtureServer.Reply.ok(feed()));

    read("1706.03762");

    assertThat(slots).hasSize(3).containsOnly("arxiv PT3S");
    assertThat(pauses).containsExactly(Duration.ofSeconds(3), Duration.ofSeconds(3));
    assertThat(server.requests).hasSize(1);
  }

  @Test
  void whenTheSlotStaysTakenNothingIsAskedAndTheReadingIsRateLimited() {
    slotFreeAfter = Integer.MAX_VALUE;

    assertThatThrownBy(() -> read("1706.03762")).isInstanceOf(SourceFailure.RateLimited.class);

    assertThat(slots).hasSize(ArxivPaperReader.SLOT_TRIES);
    assertThat(server.requests).isEmpty();
  }

  @Test
  void aRetryOfThePolicyTakesTheSharedSlotAgainBeforeItsCall() {
    server.route("/api/query", new FixtureServer.Reply(503, "", java.util.Map.of(), 0));
    Resilience retriesOnce =
        new Resilience() {
          @Override
          public <T> T call(String policy, java.util.function.Supplier<T> work) {
            try {
              return work.get();
            } catch (RuntimeException e) {
              return work.get();
            }
          }

          @Override
          public void require(String policy) {}
        };
    ArxivPaperReader reader =
        new ArxivPaperReader(
            TestHttp.restClientBuilder(
                    Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP)
                .baseUrl(server.baseUrl())
                .build(),
            retriesOnce,
            "collector-github-awesome",
            SourceFixtures.CLOCK,
            throttle,
            pauses::add);

    assertThatThrownBy(() ->
            reader.read(List.of("1706.03762"), "github-awesome", SourceFixtures.CLOCK.instant()))
        .isInstanceOf(SourceFailure.class);

    assertThat(server.requests).hasSize(2);
    assertThat(slots).hasSize(2);
  }

  @Test
  void aSlotThatStaysTakenIsNotRetriedByThePolicy() {
    slotFreeAfter = Integer.MAX_VALUE;

    assertThatThrownBy(() -> read("1706.03762"))
        .isInstanceOfSatisfying(
            SourceFailure.RateLimited.class,
            e ->
                assertThat(new ai.helper2.sources.support.TransientSourceFailure().test(e))
                    .isFalse());
  }

  @Test
  void anIdThatIsNotAnArxivIdIsNeverSentAndTakesNoSlot() {
    assertThat(read("1706.03762&search_query=all", "../x", "")).isEmpty();
    assertThat(server.requests).isEmpty();
    assertThat(slots).isEmpty();
  }
}
