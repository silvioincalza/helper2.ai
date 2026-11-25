package ai.searchavo.sources.confstech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.scout.CollectedBatch;
import ai.searchavo.search.SourceFailure;
import ai.searchavo.search.SourceHit;
import ai.searchavo.shared.Audience;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.DateFact;
import ai.searchavo.shared.DatePrecision;
import ai.searchavo.shared.EventFormat;
import ai.searchavo.shared.EventKind;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.shared.ValueOrigin;
import ai.searchavo.sources.SourceFixtures;
import ai.searchavo.sources.support.RunInterval;
import ai.searchavo.sources.support.SourceSettings;
import ai.searchavo.testing.FixtureServer;
import ai.searchavo.testing.TestHttp;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConfsTechCollectorTest {

  static final String GENERAL_2026 =
      """
      [{"name":"Agentic Engineering Days Zurich","url":"https://www.agenticdays.com",
        "startDate":"2026-11-20","endDate":"2026-11-20","city":"Zurich","country":"Switzerland",
        "online":false},
       {"name":"Frontend Days","url":"https://frontend.example","startDate":"2026-12-01",
        "endDate":"2026-12-02","city":"Berlin","country":"Germany","online":false},
       {"name":"AI Summit Past","url":"https://past.example","startDate":"2026-03-01",
        "endDate":"2026-03-02","city":"Rome","country":"Italy"},
       {"name":"AI Day TBA","url":"https://tba.example"},
       {"name":"LLM Online Day","url":"https://llm-day.example","startDate":"2026-12-10",
        "online":true}]\
      """;

  static final String DATA_2026 =
      """
      [{"name":"Agentic Engineering Days Zurich","url":"https://www.agenticdays.com/",
        "startDate":"2026-11-20","endDate":"2026-11-20","city":"Zurich","country":"Switzerland",
        "online":false},
       {"name":"ML Systems Summit 2026","url":"https://mlops.example","startDate":"2026-11-30",
        "endDate":"2026-12-01","city":"Geneva","country":"Switzerland","online":true}]\
      """;

  final FixtureServer server = new FixtureServer();

  @AfterEach
  void stop() {
    server.close();
  }

  ConfsTechCollector collector() {
    return new ConfsTechCollector(
        new SourceSettings(true, server.baseUrl(), "", Duration.ofSeconds(2), Duration.ZERO, null),
        TestHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP),
        TestHttp.restClientBuilderWithoutRedirects(Duration.ofSeconds(2), ObservationRegistry.NOOP),
        SourceFixtures.CLOCK,
        Resilience.NONE,
        new RunInterval(Duration.ofDays(1)),
        URI.create(server.baseUrl()));
  }

  void serve2026(String foreign) {
    server.route(
        ConfsTechCollector.YEAR_PATH + "2026",
        FixtureServer.Reply.ok(
            """
            [{"name":"general.json","download_url":"%1$s/raw/general.json"},
             {"name":"data.json","download_url":"%1$s/raw/data.json"},
             {"name":"README.md","download_url":"%1$s/raw/README.md"},
             {"name":"evil.json","download_url":"%2$s"}]\
            """
                .formatted(server.baseUrl(), foreign)));
    server.route("/raw/general.json", FixtureServer.Reply.ok(GENERAL_2026));
    server.route("/raw/data.json", FixtureServer.Reply.ok(DATA_2026));
  }

  @Test
  void keeps_upcoming_ai_conferences_once_with_the_days_and_place_the_file_states() {
    serve2026("https://evil.example/evil.json");

    CollectedBatch batch = collector().collect(null, null, 500);

    assertThat(batch.getHits())
        .extracting(SourceHit::getTitle)
        .containsExactly(
            "Agentic Engineering Days Zurich 2026",
            "LLM Online Day 2026",
            "ML Systems Summit 2026");
    SourceHit zurich = batch.getHits().getFirst();
    assertThat(zurich)
        .isEqualTo(
            new SourceHit(
                "confs-tech",
                "https://agenticdays.com",
                URI.create("https://www.agenticdays.com"),
                CanonicalUrl.of("https://agenticdays.com"),
                null,
                "Agentic Engineering Days Zurich 2026",
                null,
                ValueOrigin.SOURCE_PROVIDED,
                ResourceType.EVENT,
                Audience.BUILD_WITH_AI,
                null,
                Set.of(),
                Set.of("conference"),
                null,
                null,
                Set.of(),
                null,
                DateFact.unknown(),
                DateFact.unknown(),
                List.of(),
                SourceFixtures.CLOCK.instant(),
                0,
                new DateFact(
                    Instant.parse("2026-11-20T00:00:00Z"),
                    DatePrecision.DAY,
                    "confs-tech",
                    List.of(),
                    false),
                new DateFact(
                    Instant.parse("2026-11-20T00:00:00Z"),
                    DatePrecision.DAY,
                    "confs-tech",
                    List.of(),
                    false),
                "Zurich, Switzerland",
                EventKind.CONFERENCE,
                EventFormat.IN_PERSON));
    assertThat(zurich.getSourceId()).isEqualTo(ConfsTechCollector.ID);
    assertThat(zurich.getType()).isEqualTo(ResourceType.EVENT);
    assertThat(zurich.getEventKind()).isEqualTo(EventKind.CONFERENCE);
    assertThat(zurich.getEventFormat()).isEqualTo(EventFormat.IN_PERSON);
    assertThat(zurich.getLocation()).isEqualTo("Zurich, Switzerland");
    assertThat(zurich.getEventStart().getValue()).hasToString("2026-11-20T00:00:00Z");
    assertThat(zurich.getEventStart().getPrecision()).isEqualTo(DatePrecision.DAY);
    assertThat(zurich.getEventStart().getSourceId()).isEqualTo(ConfsTechCollector.ID);
    assertThat(zurich.getEventEnd().getValue()).hasToString("2026-11-20T00:00:00Z");
    assertThat(batch.getCheckpoint()).isEqualTo(SourceFixtures.CLOCK.instant().toString());
  }

  @Test
  void an_online_conference_without_a_city_is_online_and_with_a_city_hybrid() {
    serve2026("https://evil.example/evil.json");

    List<SourceHit> hits = collector().collect(null, null, 500).getHits();

    assertThat(hits.get(1).getEventFormat()).isEqualTo(EventFormat.ONLINE);
    assertThat(hits.get(1).getLocation()).isNull();
    assertThat(hits.get(1).getEventEnd().getValue()).hasToString("2026-12-10T00:00:00Z");
    assertThat(hits.get(2).getEventFormat()).isEqualTo(EventFormat.HYBRID);
  }

  @Test
  void reads_only_files_on_the_raw_host_and_a_missing_next_year_is_no_error() {
    serve2026("https://evil.example/evil.json");

    collector().collect(null, null, 500);

    assertThat(server.requests)
        .containsExactly(
            ConfsTechCollector.YEAR_PATH + "2026",
            "/raw/general.json",
            "/raw/data.json",
            ConfsTechCollector.YEAR_PATH + "2027");
  }

  @Test
  void a_pass_that_is_not_due_calls_nothing() {
    CollectedBatch batch =
        collector().collect(SourceFixtures.CLOCK.instant().toString(), null, 500);

    assertThat(batch.getNotDue()).isTrue();
    assertThat(server.requests).isEmpty();
  }

  @Test
  void a_failed_year_listing_fails_the_pass_so_it_is_tried_again() {
    server.route(
        ConfsTechCollector.YEAR_PATH + "2026",
        new FixtureServer.Reply(500, "{}", java.util.Map.of(), 0));

    assertThatThrownBy(() -> collector().collect(null, null, 500))
        .isInstanceOf(SourceFailure.class);
  }

  @Test
  void a_raw_file_that_redirects_is_not_followed() {
    server.route(
        ConfsTechCollector.YEAR_PATH + "2026",
        FixtureServer.Reply.ok(
            """
            [{"name":"general.json","download_url":"%s/raw/general.json"}]\
            """
                .formatted(server.baseUrl())));
    server.route(
        "/raw/general.json",
        new FixtureServer.Reply(
            302, "", java.util.Map.of("Location", server.baseUrl() + "/elsewhere.json"), 0));
    server.route("/elsewhere.json", FixtureServer.Reply.ok(GENERAL_2026));

    CollectedBatch batch = collector().collect(null, null, 500);

    assertThat(batch.getHits()).isEmpty();
    assertThat(server.requests).doesNotContain("/elsewhere.json");
  }

  @Test
  void once_the_item_limit_is_reached_no_further_file_or_year_is_requested() {
    serve2026("https://evil.example/evil.json");

    CollectedBatch batch = collector().collect(null, null, 1);

    assertThat(batch.getHits()).hasSize(1);
    assertThat(server.requests)
        .containsExactly(ConfsTechCollector.YEAR_PATH + "2026", "/raw/general.json");
  }
}
