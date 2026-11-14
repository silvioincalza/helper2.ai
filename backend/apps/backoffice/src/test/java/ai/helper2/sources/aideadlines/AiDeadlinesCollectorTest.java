package ai.helper2.sources.aideadlines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.resilience.CallRefused;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.DatePrecision;
import ai.helper2.shared.EventKind;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AiDeadlinesCollectorTest {
  static final String NEURIPS =
      """
      - title: NeurIPS
        year: 2025
        id: neurips25
        full_name: Conference on Neural Information Processing Systems
        link: https://neurips.cc/
        city: San Diego
        country: USA
        start: '2025-11-30'
        end: '2025-12-07'
      - title: NeurIPS
        year: 2026
        id: neurips26
        full_name: Conference on Neural Information Processing Systems
        link: https://neurips.cc/
        city: Sydney
        country: Australia
        venue: International Convention Centre
        start: 2026-12-06
        end: 2026-12-12
      """;
  static final String TBA =
      """
      - title: FutureConf
        year: 2027
        link: https://futureconf.example.org
        start: TBA
      """;

  final FixtureServer server = new FixtureServer();
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  {
    Metrics.addRegistry(meters);
  }

  @AfterEach
  void stop() {
    server.close();
    Metrics.removeRegistry(meters);
  }

  private double dropped(String reason) {
    var counter =
        meters
            .find("helper2.source.items.dropped")
            .tag("source", "ai-deadlines")
            .tag("reason", reason)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private double droppedInAll() {
    return meters
        .find("helper2.source.items.dropped")
        .tag("source", "ai-deadlines")
        .counters()
        .stream()
        .mapToDouble(c -> c.count())
        .sum();
  }

  private AiDeadlinesCollector collector(Resilience resilience) {
    return new AiDeadlinesCollector(
        new SourceSettings(true, server.baseUrl(), "", Duration.ofSeconds(2), Duration.ZERO),
        TestHttp.restClientBuilder(
            Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
        SourceFixtures.CLOCK,
        resilience,
        new RunInterval(Duration.ofDays(1)),
        URI.create(server.baseUrl()));
  }

  @Test
  void rollsEachConferenceToItsNextEditionWithStatedDays() {
    server.route(
        AiDeadlinesCollector.CONFERENCES_PATH,
        FixtureServer.Reply.ok(
            """
            [{"name":"neurips.yml","download_url":"%1$s/raw/neurips.yml"},
             {"name":"futureconf.yml","download_url":"%1$s/raw/futureconf.yml"},
             {"name":"README.md","download_url":"%1$s/raw/README.md"}]\
            """
                .formatted(server.baseUrl())));
    server.route("/raw/neurips.yml", FixtureServer.Reply.ok(NEURIPS));
    server.route("/raw/futureconf.yml", FixtureServer.Reply.ok(TBA));
    AiDeadlinesCollector collector =
        new AiDeadlinesCollector(
            new SourceSettings(true, server.baseUrl(), "", Duration.ofSeconds(2), Duration.ZERO),
            TestHttp.restClientBuilder(
                Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
            SourceFixtures.CLOCK,
            Resilience.NONE,
            new RunInterval(Duration.ofDays(1)),
            URI.create(server.baseUrl()));

    CollectedBatch batch = collector.collect(null, null, 500);

    assertThat(batch.getHits()).hasSize(1);
    SourceHit hit = batch.getHits().getFirst();
    assertThat(hit.getExternalId()).isEqualTo("neurips");
    assertThat(hit.getTitle()).isEqualTo("NeurIPS 2026");
    assertThat(hit.getType()).isEqualTo(ResourceType.EVENT);
    assertThat(hit.getLocation()).isEqualTo("Sydney, Australia");
    assertThat(hit.getEventKind()).isEqualTo(EventKind.CONFERENCE);
    assertThat(hit.getEventFormat()).isNull();
    assertThat(hit.getDescription())
        .isEqualTo(
            "Conference on Neural Information Processing Systems. International Convention Centre");
    assertThat(hit.getEventStart().getValue()).isEqualTo(Instant.parse("2026-12-06T00:00:00Z"));
    assertThat(hit.getEventStart().getPrecision()).isEqualTo(DatePrecision.DAY);
    assertThat(hit.getEventEnd().getValue()).isEqualTo(Instant.parse("2026-12-12T00:00:00Z"));
    assertThat(hit.getPublished().getValue()).isNull();
  }

  @Test
  void rawFilesComeOnlyFromTheRawHostWithoutTheTokenAndABadFileIsSkipped() {
    String foreign = server.baseUrl().replace("127.0.0.1", "localhost");
    server.route(
        AiDeadlinesCollector.CONFERENCES_PATH,
        FixtureServer.Reply.ok(
            """
            [{"name":"elsewhere.yml","download_url":"%2$s/raw/elsewhere.yml"},
             {"name":"broken.yml","download_url":"%1$s/raw/broken.yml"},
             {"name":"neurips.yml","download_url":"%1$s/raw/neurips.yml"}]\
            """
                .formatted(server.baseUrl(), foreign)));
    server.route("/raw/broken.yml", new FixtureServer.Reply(500, "oops", Map.of(), 0));
    server.route("/raw/neurips.yml", FixtureServer.Reply.ok(NEURIPS));
    AiDeadlinesCollector collector =
        new AiDeadlinesCollector(
            new SourceSettings(
                true, server.baseUrl(), "secret-token", Duration.ofSeconds(2), Duration.ZERO),
            TestHttp.restClientBuilder(
                Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
            SourceFixtures.CLOCK,
            Resilience.NONE,
            new RunInterval(Duration.ofDays(1)),
            URI.create(server.baseUrl()));

    CollectedBatch batch = collector.collect(null, null, 500);

    assertThat(batch.getHits()).extracting(SourceHit::getExternalId).containsExactly("neurips");
    assertThat(server.requests).noneMatch(r -> r.contains("elsewhere"));
    assertThat(dropped("ForeignDownloadUrl")).isEqualTo(1);
    assertThat(dropped("Unavailable")).as("the HTTP 500 file").isEqualTo(1);
    for (int i = 0; i < server.requests.size(); i++) {
      boolean api = server.requests.get(i).startsWith(AiDeadlinesCollector.CONFERENCES_PATH);
      assertThat(server.requestHeaders.get(i).containsKey("Authorization"))
          .as(server.requests.get(i))
          .isEqualTo(api);
    }
  }

  @Test
  void onlyTheRealRawHostIsAllowedByDefault() {
    AiDeadlinesCollector collector =
        new AiDeadlinesCollector(
            new SourceSettings(
                true, "https://api.github.com", "", Duration.ofSeconds(2), Duration.ZERO),
            TestHttp.restClientBuilder(
                Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
            SourceFixtures.CLOCK,
            Resilience.NONE,
            new RunInterval(Duration.ofDays(1)));
    assertThat(
            collector.allowed(
                "https://raw.githubusercontent.com/huggingface/ai-deadlines/main/x.yml"))
        .isPresent();
    assertThat(collector.allowed("https://evil.example/x.yml")).isEmpty();
    assertThat(collector.allowed("http://raw.githubusercontent.com/x.yml")).isEmpty();
    assertThat(collector.allowed("https://user@raw.githubusercontent.com/x.yml")).isEmpty();
  }

  @Test
  void anEventStillRunningTodayIsNext() {
    String running =
        """
        - title: Conf
          year: 2026
          link: https://conf.example.org
          start: '2026-10-25'
          end: '2026-10-30'
        """;
    LocalDate today = LocalDate.parse("2026-10-27");
    assertThat(AiDeadlinesCollector.nextEdition("conf", running, today, Instant.now(), 0))
        .isPresent();
    assertThat(
            AiDeadlinesCollector.nextEdition(
                "conf", running, LocalDate.parse("2026-10-31"), Instant.now(), 0))
        .isEmpty();
  }

  @Test
  void aConferenceLinkThatIsNoWebLinkIsShownInItsCanonicalForm() {
    String gitLink =
        """
        - title: Conf
          year: 2026
          link: git+https://github.com/acme/conf.git
          start: '2026-12-01'
        """;

    assertThat(
            AiDeadlinesCollector.nextEdition(
                "conf", gitLink, LocalDate.parse("2026-10-27"), Instant.now(), 0))
        .hasValueSatisfying(
            h -> assertThat(h.getUrl()).isEqualTo(URI.create("https://github.com/acme/conf")));
  }

  @Test
  void aFileThatIsNoValidYamlIsCountedButAConferenceWithoutUpcomingEditionIsNot() {
    server.route(
        AiDeadlinesCollector.CONFERENCES_PATH,
        FixtureServer.Reply.ok(
            """
            [{"name":"bad.yml","download_url":"%1$s/raw/bad.yml"},
             {"name":"futureconf.yml","download_url":"%1$s/raw/futureconf.yml"}]\
            """
                .formatted(server.baseUrl())));
    server.route("/raw/bad.yml", FixtureServer.Reply.ok("- title: [unclosed"));
    server.route("/raw/futureconf.yml", FixtureServer.Reply.ok(TBA));

    CollectedBatch batch = collector(Resilience.NONE).collect(null, null, 500);

    assertThat(batch.getHits()).isEmpty();
    assertThat(droppedInAll()).as("only the broken file").isEqualTo(1);
  }

  @Test
  void anOpenCircuitEndsThePassWithoutCountingEachFile() {
    server.route(
        AiDeadlinesCollector.CONFERENCES_PATH,
        FixtureServer.Reply.ok(
            """
            [{"name":"neurips.yml","download_url":"%1$s/raw/neurips.yml"},
             {"name":"a.yml","download_url":"%1$s/raw/a.yml"},
             {"name":"b.yml","download_url":"%1$s/raw/b.yml"}]\
            """
                .formatted(server.baseUrl())));
    server.route("/raw/neurips.yml", FixtureServer.Reply.ok(NEURIPS));
    AtomicInteger calls = new AtomicInteger();
    Resilience opensAfterTwoCalls =
        new Resilience() {
          @Override
          public <T> T call(String policy, Supplier<T> work) {
            if (calls.incrementAndGet() > 2) {
              throw new CallRefused(policy, CallRefused.Reason.CIRCUIT_OPEN, null);
            }
            return work.get();
          }

          @Override
          public void require(String policy) {}
        };

    assertThatThrownBy(() -> collector(opensAfterTwoCalls).collect(null, null, 500))
        .isInstanceOf(CallRefused.class);
    assertThat(calls).hasValue(3);
    assertThat(droppedInAll()).isZero();
  }
}
