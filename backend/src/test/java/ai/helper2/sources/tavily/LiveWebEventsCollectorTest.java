package ai.helper2.sources.tavily;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.quota.QuotaKeys;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.LlmUnavailable;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.EventFormat;
import ai.helper2.shared.EventKind;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.events.EventReader;
import ai.helper2.sources.events.EventReading;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LiveWebEventsCollectorTest {

  static final String SEARCH =
      """
      {"results":[
        {"url":"https://hack.example/zurich-2027","title":"Zurich AI Builders Hackathon 2027",
         "content":"Build AI agents with open models over one weekend."},
        {"url":"https://summit.example/ai","title":"AI Summit Europe",
         "content":"The AI summit for builders."},
        {"url":"http://plain.example/ai-meetup","title":"AI meetup","content":"An AI meetup, no https."},
        {"url":"https://garden.example/roses","title":"Rose show","content":"Roses and tulips."}
      ],"usage":{"credits":1}}\
      """;

  static final String HACK_PAGE =
      "# Zurich AI Builders Hackathon 2027\\n\\n**When:** 12–14 March 2027\\n\\n"
          + "This is an in-person hackathon at ETH Zurich, Switzerland.";
  static final String SUMMIT_PAGE =
      "AI Summit Europe takes place on 20–21 January 2027 in Amsterdam."
          + " Questions: anna@summit.example or +41 44 123 45 67.";

  static final String EXTRACT =
      """
      {"results":[
        {"url":"https://hack.example/zurich-2027","raw_content":"%s"},
        {"url":"https://summit.example/ai","raw_content":"%s"}
      ],"failed_results":[],"usage":{"credits":1}}\
      """
          .formatted(HACK_PAGE, SUMMIT_PAGE);

  final FixtureServer server = new FixtureServer();
  final TavilyDiscoveryCollectorTest.MapCounter counter =
      new TavilyDiscoveryCollectorTest.MapCounter();
  final QuotaKeys keys = new QuotaKeys("", SourceFixtures.CLOCK);
  final List<String> pagesRead = new ArrayList<>();
  final List<String> textsRead = new ArrayList<>();

  @AfterEach
  void stop() {
    server.close();
  }

  EventReader reader(Function<String, Optional<EventReading>> answer) {
    return new EventReader() {
      @Override
      public String model() {
        return "nemotron-super";
      }

      @Override
      public String promptVersion() {
        return "p1";
      }

      @Override
      public Optional<EventReading> read(String title, String pageText) {
        pagesRead.add(title);
        textsRead.add(pageText);
        return answer.apply(title);
      }
    };
  }

  /** The hackathon read right; the summit read with days its quote does not state. */
  static Optional<EventReading> readsBoth(String title) {
    if (title.startsWith("Zurich")) {
      return Optional.of(
          new EventReading(
              1,
              LocalDate.parse("2027-03-12"),
              LocalDate.parse("2027-03-14"),
              "**When:** 12–14 March 2027",
              EventKind.HACKATHON,
              "This is an in-person hackathon",
              EventFormat.IN_PERSON,
              "This is an in-person hackathon",
              "ETH Zurich, Switzerland",
              "at ETH Zurich, Switzerland."));
    }
    return Optional.of(
        new EventReading(
            1,
            LocalDate.parse("2027-01-22"),
            null,
            "takes place on 20–21 January 2027 in Amsterdam",
            null,
            null,
            null,
            null,
            null,
            null));
  }

  LiveWebEventsCollector collector(long creditCap, EventReader reader) {
    return new LiveWebEventsCollector(
        new SourceSettings(
            true, server.baseUrl(), "tvly-test", Duration.ofSeconds(2), Duration.ZERO),
        ObservedHttp.restClientBuilder(
            Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
        SourceFixtures.CLOCK,
        Resilience.NONE,
        new RunInterval(Duration.ofDays(7)),
        List.of("AI hackathons", "AI conferences and summits"),
        20,
        new ScoutTavilyCredits(counter, keys, creditCap, new SimpleMeterRegistry()),
        reader);
  }

  @Test
  void lists_an_event_only_when_the_gate_finds_its_days_in_the_pages_own_words() {
    server.route("/search", FixtureServer.Reply.ok(SEARCH));
    server.route("/extract", FixtureServer.Reply.ok(EXTRACT));

    CollectedBatch batch =
        collector(40, reader(LiveWebEventsCollectorTest::readsBoth)).collect(null, null, 500);

    assertThat(batch.getHits()).hasSize(1);
    SourceHit event = batch.getHits().getFirst();
    assertThat(event.getSourceId()).isEqualTo(LiveWebEventsCollector.ID);
    assertThat(event.getType()).isEqualTo(ResourceType.EVENT);
    assertThat(event.getTitle()).isEqualTo("Zurich AI Builders Hackathon 2027");
    assertThat(event.getEventStart().getValue()).hasToString("2027-03-12T00:00:00Z");
    assertThat(event.getEventStart().getSourceId()).isEqualTo(LiveWebEventsCollector.ID);
    assertThat(event.getEventEnd().getValue()).hasToString("2027-03-14T00:00:00Z");
    assertThat(event.getEventKind()).isEqualTo(EventKind.HACKATHON);
    assertThat(event.getEventFormat()).isEqualTo(EventFormat.IN_PERSON);
    assertThat(event.getLocation()).isEqualTo("ETH Zurich, Switzerland");
    assertThat(event.getFacts())
        .containsEntry("query", "AI hackathons")
        .containsEntry(
            "eventEvidence",
            Map.of(
                "dateQuote", "**When:** 12–14 March 2027",
                "model", "nemotron-super",
                "promptVersion", "p1"));
    assertThat(batch.getCheckpoint()).isEqualTo(SourceFixtures.CLOCK.instant().toString());
  }

  @Test
  void searches_twice_reads_only_plain_https_ai_pages_once_each_and_counts_every_credit() {
    server.route("/search", FixtureServer.Reply.ok(SEARCH));
    server.route("/extract", FixtureServer.Reply.ok(EXTRACT));

    collector(40, reader(LiveWebEventsCollectorTest::readsBoth)).collect(null, null, 500);

    assertThat(server.requests).containsExactly("/search", "/search", "/extract");
    assertThat(server.requestBodies.getFirst())
        .contains("\"query\":\"AI hackathons\"")
        .contains("\"topic\":\"general\"")
        .contains("\"search_depth\":\"basic\"")
        .contains("\"include_usage\":true");
    assertThat(server.requestBodies.getLast())
        .contains("\"urls\":[\"https://hack.example/zurich-2027\",\"https://summit.example/ai\"]")
        .contains("\"extract_depth\":\"basic\"");
    assertThat(pagesRead).containsExactly("Zurich AI Builders Hackathon 2027", "AI Summit Europe");
    assertThat(counter.counts.values()).contains(3L);
  }

  @Test
  void without_a_model_or_over_the_credit_cap_it_calls_nothing() {
    server.route("/search", FixtureServer.Reply.ok(SEARCH));

    CollectedBatch noModel = collector(40, null).collect(null, null, 500);
    CollectedBatch overCap =
        collector(0, reader(LiveWebEventsCollectorTest::readsBoth)).collect(null, null, 500);

    assertThat(noModel.getNotDue()).isTrue();
    assertThat(overCap.getNotDue()).isTrue();
    assertThat(server.requests).isEmpty();
    assertThat(collector(40, null).getDescriptor().getEnabled()).isFalse();
  }

  @Test
  void when_the_model_runs_out_it_keeps_the_events_already_read() {
    server.route("/search", FixtureServer.Reply.ok(SEARCH));
    server.route("/extract", FixtureServer.Reply.ok(EXTRACT));
    EventReader outAfterOne =
        reader(title -> {
          if (pagesRead.size() > 1) {
            throw new LlmUnavailable("daily event reader budget used up");
          }
          return readsBoth(title);
        });

    CollectedBatch batch = collector(40, outAfterOne).collect(null, null, 500);

    assertThat(batch.getHits())
        .extracting(SourceHit::getTitle)
        .containsExactly("Zurich AI Builders Hackathon 2027");
  }

  @Test
  void the_model_never_sees_an_email_address_or_a_phone_number_of_the_page() {
    server.route("/search", FixtureServer.Reply.ok(SEARCH));
    server.route("/extract", FixtureServer.Reply.ok(EXTRACT));

    collector(40, reader(LiveWebEventsCollectorTest::readsBoth)).collect(null, null, 500);

    assertThat(textsRead.getLast())
        .contains("[email]", "[phone]")
        .doesNotContain("anna@summit.example", "123 45 67");
  }
}
