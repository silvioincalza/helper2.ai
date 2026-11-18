package ai.helper2.sources.liveweb.scout;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.quota.QuotaKeys;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.LlmUnavailable;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.EventFormat;
import ai.helper2.shared.EventKind;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.events.EventReader;
import ai.helper2.sources.events.EventReading;
import ai.helper2.sources.events.UndatedEvents;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
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
  final DiscoveryCollectorTest.MapCounter counter = new DiscoveryCollectorTest.MapCounter();
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
    return collector(creditCap, reader, UndatedEvents.NONE);
  }

  LiveWebEventsCollector collector(long creditCap, EventReader reader, UndatedEvents undated) {
    return new LiveWebEventsCollector(
        new SourceSettings(
            true, server.baseUrl(), "tvly-test", Duration.ofSeconds(2), Duration.ZERO),
        TestHttp.restClientBuilder(
            Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
        SourceFixtures.CLOCK,
        Resilience.NONE,
        new RunInterval(Duration.ofDays(7)),
        List.of("AI hackathons", "AI conferences and summits"),
        20,
        new ScoutLiveWebCredits(counter, keys, creditCap, new SimpleMeterRegistry()),
        reader,
        undated,
        SourceFixtures.SHADOW_LIBRARIES);
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
  void when_the_credit_cap_stops_the_page_reads_the_queries_are_searched_again_next_time() {
    server.route("/search", FixtureServer.Reply.ok(SEARCH));
    server.route("/extract", FixtureServer.Reply.ok(EXTRACT));

    CollectedBatch batch =
        collector(2, reader(LiveWebEventsCollectorTest::readsBoth)).collect(null, null, 500);

    assertThat(server.requests).as("two searches, no page read").hasSize(2);
    assertThat(batch.getHits()).isEmpty();
    assertThat(batch.getCheckpoint()).as("the pass stays open").isNull();
    assertThat(QueryProgress.done(batch.getNextCursor()))
        .as("no query is done: its pages were not read; the empty catalog is")
        .containsExactly(LiveWebEventsCollector.UNDATED);
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
    assertThat(batch.getCheckpoint()).isNull();
    assertThat(QueryProgress.done(batch.getNextCursor()))
        .containsExactly(LiveWebEventsCollector.UNDATED);
  }

  @Test
  void a_pass_left_open_searches_only_the_queries_not_done_and_then_moves_its_checkpoint() {
    server.route("/search", FixtureServer.Reply.ok(SEARCH));
    server.route("/extract", FixtureServer.Reply.ok(EXTRACT));

    CollectedBatch batch =
        collector(40, reader(LiveWebEventsCollectorTest::readsBoth))
            .collect(null, QueryProgress.cursor(List.of("AI hackathons")), 500);

    assertThat(server.requests).containsExactly("/search", "/extract");
    assertThat(server.requestBodies.getFirst()).contains("AI conferences and summits");
    assertThat(batch.getNextCursor()).isNull();
    assertThat(batch.getCheckpoint()).isEqualTo(SourceFixtures.CLOCK.instant().toString());
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

  static SourceHit page(String url) {
    return new SourceHit(
        LiveWebEventsCollector.ID,
        url,
        java.net.URI.create(url),
        ai.helper2.shared.CanonicalUrl.of(url),
        null,
        "AI event",
        null,
        ai.helper2.shared.ValueOrigin.SOURCE_PROVIDED,
        ResourceType.EVENT,
        ai.helper2.shared.Audience.BOTH,
        null,
        java.util.Set.of(),
        java.util.Set.of(),
        null,
        null,
        java.util.Set.of(),
        null,
        ai.helper2.shared.DateFact.unknown(),
        ai.helper2.shared.DateFact.unknown(),
        List.of(),
        SourceFixtures.CLOCK.instant(),
        0);
  }

  @Test
  void every_query_gets_its_share_of_the_pages_read() {
    List<SourceHit> hackathons =
        List.of(
            page("https://h.example/1"), page("https://h.example/2"), page("https://h.example/3"));
    List<SourceHit> workshops =
        List.of(
            page("https://w.example/1"), page("https://h.example/2"), page("https://w.example/3"));

    assertThat(LiveWebEventsCollector.interleaved(List.of(hackathons, workshops), 4))
        .extracting(h -> h.getCanonicalUrl().getValue())
        .containsExactly(
            "https://h.example/1",
            "https://w.example/1",
            "https://h.example/2",
            "https://h.example/3");
  }

  static final String NO_RESULTS = "{\"results\":[],\"usage\":{\"credits\":1}}";

  static final String CONFERENCE_PAGE =
      "Agentic Engineering Days Zurich 2026 November 20, 2026 · Marriott Hotel, Zurich Main Station";

  static final String CONFERENCE_EXTRACT =
      """
      {"results":[
        {"url":"https://www.agenticdays.com/","raw_content":"%s"}
      ],"failed_results":[],"usage":{"credits":1}}\
      """
          .formatted(CONFERENCE_PAGE);

  static Optional<EventReading> readsConference(String title) {
    return Optional.of(
        new EventReading(
            1,
            LocalDate.parse("2026-11-20"),
            LocalDate.parse("2026-11-20"),
            CONFERENCE_PAGE,
            null,
            null,
            null,
            null,
            null,
            null));
  }

  @Test
  void an_undated_catalog_event_gets_the_days_its_own_page_states() {
    server.route("/search", FixtureServer.Reply.ok(NO_RESULTS));
    server.route("/extract", FixtureServer.Reply.ok(CONFERENCE_EXTRACT));
    List<java.time.Instant> asked = new ArrayList<>();
    UndatedEvents undated =
        (since, after, limit) -> {
          asked.add(since);
          return List.of(
              new UndatedEvents.Page(
                  "https://www.agenticdays.com/",
                  "Agentic Engineering Days Zurich 2026",
                  position(1)));
        };

    CollectedBatch batch =
        collector(40, reader(LiveWebEventsCollectorTest::readsConference), undated)
            .collect(null, null, 500);

    assertThat(asked)
        .containsExactly(
            SourceFixtures.CLOCK.instant().minus(LiveWebEventsCollector.UNDATED_LOOKBACK));
    assertThat(batch.getHits()).hasSize(1);
    SourceHit event = batch.getHits().getFirst();
    assertThat(event.getCanonicalUrl().getHost()).isEqualTo("agenticdays.com");
    assertThat(event.getType()).isEqualTo(ResourceType.EVENT);
    assertThat(event.getEventStart().getValue()).hasToString("2026-11-20T00:00:00Z");
    assertThat(event.getFacts()).containsEntry("query", LiveWebEventsCollector.UNDATED);
    assertThat(batch.getCheckpoint()).isEqualTo(SourceFixtures.CLOCK.instant().toString());
  }

  @Test
  void a_later_pass_reads_only_the_undated_events_found_since_the_last_pass() {
    server.route("/search", FixtureServer.Reply.ok(NO_RESULTS));
    List<java.time.Instant> asked = new ArrayList<>();
    UndatedEvents undated =
        (since, after, limit) -> {
          asked.add(since);
          return List.of();
        };
    String lastPass = SourceFixtures.CLOCK.instant().minus(Duration.ofDays(8)).toString();

    collector(40, reader(LiveWebEventsCollectorTest::readsConference), undated)
        .collect(lastPass, null, 500);

    assertThat(asked).containsExactly(java.time.Instant.parse(lastPass));
  }

  @Test
  void a_pass_that_already_read_the_undated_events_does_not_read_them_again() {
    server.route("/search", FixtureServer.Reply.ok(NO_RESULTS));
    List<java.time.Instant> asked = new ArrayList<>();
    UndatedEvents undated =
        (since, after, limit) -> {
          asked.add(since);
          return List.of();
        };

    collector(40, reader(LiveWebEventsCollectorTest::readsConference), undated)
        .collect(null, QueryProgress.cursor(List.of(LiveWebEventsCollector.UNDATED)), 500);

    assertThat(asked).isEmpty();
  }

  static UndatedEvents.Position position(int n) {
    return new UndatedEvents.Position(
        SourceFixtures.CLOCK.instant().minus(Duration.ofDays(10)).plusSeconds(n),
        new java.util.UUID(0L, n));
  }

  @Test
  void a_full_batch_of_undated_events_keeps_the_pass_open_and_the_next_call_reads_on() {
    server.route("/search", FixtureServer.Reply.ok(NO_RESULTS));
    server.route("/extract", FixtureServer.Reply.ok(NO_RESULTS));
    List<UndatedEvents.Position> askedAfter = new ArrayList<>();
    UndatedEvents undated =
        (since, after, limit) -> {
          askedAfter.add(after);
          int from = after == null ? 0 : (int) after.id().getLeastSignificantBits();
          int count = after == null ? limit : 1;
          List<UndatedEvents.Page> batch = new ArrayList<>();
          for (int n = from + 1; n <= from + count; n++) {
            batch.add(
                new UndatedEvents.Page(
                    "https://event" + n + ".example/", "AI event " + n, position(n)));
          }
          return batch;
        };
    LiveWebEventsCollector collector =
        collector(40, reader(LiveWebEventsCollectorTest::readsConference), undated);

    CollectedBatch first = collector.collect(null, null, 500);

    assertThat(first.getCheckpoint()).as("the pass stays open").isNull();
    assertThat(QueryProgress.done(first.getNextCursor()))
        .contains(LiveWebEventsCollector.UNDATED_AFTER + position(20).token())
        .doesNotContain(LiveWebEventsCollector.UNDATED);

    CollectedBatch second = collector.collect(null, first.getNextCursor(), 500);

    assertThat(askedAfter).containsExactly(null, position(20));
    assertThat(second.getNextCursor()).isNull();
    assertThat(second.getCheckpoint()).isEqualTo(SourceFixtures.CLOCK.instant().toString());
  }

  @Test
  void undated_events_have_their_own_page_budget_next_to_the_search_pages() {
    server.route("/search", FixtureServer.Reply.ok(SEARCH));
    server.route("/extract", FixtureServer.Reply.ok(EXTRACT));
    UndatedEvents undated =
        (since, after, limit) ->
            List.of(
                new UndatedEvents.Page(
                    "https://www.agenticdays.com/",
                    "Agentic Engineering Days Zurich 2026",
                    position(1)));

    collector(40, reader(LiveWebEventsCollectorTest::readsBoth), undated).collect(null, null, 500);

    assertThat(server.requestBodies.getLast())
        .contains("\"https://hack.example/zurich-2027\"")
        .contains("\"https://summit.example/ai\"")
        .contains("agenticdays.com");
  }

  @Test
  void a_failed_page_read_keeps_the_catalog_position_for_the_next_call() {
    server.route("/search", FixtureServer.Reply.ok(NO_RESULTS));
    server.route("/extract", new FixtureServer.Reply(500, "{}", java.util.Map.of(), 0));
    UndatedEvents undated =
        (since, after, limit) ->
            List.of(
                new UndatedEvents.Page(
                    "https://www.agenticdays.com/",
                    "Agentic Engineering Days Zurich 2026",
                    position(1)));

    CollectedBatch batch =
        collector(40, reader(LiveWebEventsCollectorTest::readsConference), undated)
            .collect(null, null, 500);

    assertThat(batch.getCheckpoint()).isNull();
    assertThat(QueryProgress.done(batch.getNextCursor()))
        .doesNotContain(LiveWebEventsCollector.UNDATED)
        .noneMatch(key -> key.startsWith(LiveWebEventsCollector.UNDATED_AFTER));
  }

  @Test
  void a_page_found_by_search_and_in_the_catalog_is_read_once_as_a_catalog_page() {
    server.route("/search", FixtureServer.Reply.ok(SEARCH));
    server.route("/extract", FixtureServer.Reply.ok(EXTRACT));
    UndatedEvents undated =
        (since, after, limit) ->
            List.of(
                new UndatedEvents.Page(
                    "https://hack.example/zurich-2027",
                    "Zurich AI Builders Hackathon 2027",
                    position(1)));

    CollectedBatch batch =
        collector(40, reader(LiveWebEventsCollectorTest::readsBoth), undated)
            .collect(null, null, 500);

    assertThat(pagesRead).containsOnlyOnce("Zurich AI Builders Hackathon 2027");
    assertThat(batch.getHits())
        .filteredOn(h -> h.getTitle().startsWith("Zurich"))
        .singleElement()
        .satisfies(
            h -> assertThat(h.getFacts()).containsEntry("query", LiveWebEventsCollector.UNDATED));
  }

  @Test
  void searches_whose_pages_were_all_read_stay_done_when_the_model_runs_out_on_catalog_pages() {
    server.route("/search", FixtureServer.Reply.ok(NO_RESULTS));
    server.route("/extract", FixtureServer.Reply.ok(CONFERENCE_EXTRACT));
    UndatedEvents undated =
        (since, after, limit) ->
            List.of(
                new UndatedEvents.Page(
                    "https://www.agenticdays.com/",
                    "Agentic Engineering Days Zurich 2026",
                    position(1)));
    EventReader out =
        reader(title -> {
          throw new LlmUnavailable("daily event reader budget used up");
        });

    CollectedBatch batch = collector(40, out, undated).collect(null, null, 500);

    assertThat(QueryProgress.done(batch.getNextCursor()))
        .contains("AI hackathons", "AI conferences and summits")
        .doesNotContain(LiveWebEventsCollector.UNDATED);
  }

  static final String POST_URL =
      "https://www.linkedin.com/posts/aimlevents_agentic-engineering-days-zurich-2026-activity-1";

  static final String POST_AND_SITE_EXTRACT =
      """
      {"results":[
        {"url":"%s","raw_content":"Agentic Engineering Days Zurich 2026 is coming. [Details](https://www.agenticdays.com/) Tickets https://lnkd.in/abc"},
        {"url":"https://www.agenticdays.com/","raw_content":"%s"}
      ],"failed_results":[],"usage":{"credits":1}}\
      """
          .formatted(POST_URL, CONFERENCE_PAGE);

  @Test
  void an_undated_linkedin_post_leads_to_the_event_on_its_own_site() {
    server.route("/search", FixtureServer.Reply.ok(NO_RESULTS));
    server.route("/extract", FixtureServer.Reply.ok(POST_AND_SITE_EXTRACT));
    UndatedEvents undated =
        (since, after, limit) ->
            List.of(
                new UndatedEvents.Page(
                    POST_URL,
                    "Agentic Engineering Days Zurich 2026 | The AI Coding & Agents Conference | AI & ML Events",
                    position(1)));
    EventReader onlyTheSite =
        reader(title ->
            title.equals("Agentic Engineering Days Zurich 2026")
                ? readsConference(title)
                : Optional.empty());

    CollectedBatch batch = collector(40, onlyTheSite, undated).collect(null, null, 500);

    assertThat(batch.getHits())
        .singleElement()
        .satisfies(event -> {
          assertThat(event.getCanonicalUrl().getHost()).isEqualTo("agenticdays.com");
          assertThat(event.getTitle()).isEqualTo("Agentic Engineering Days Zurich 2026");
          assertThat(event.getEventStart().getValue()).hasToString("2026-11-20T00:00:00Z");
          assertThat(event.getFacts())
              .containsEntry("query", LiveWebEventsCollector.UNDATED)
              .containsEntry("linkedFrom", CanonicalUrl.of(POST_URL).getValue());
        });
    assertThat(server.requestBodies.getLast()).contains("agenticdays.com");
    assertThat(batch.getCheckpoint()).isEqualTo(SourceFixtures.CLOCK.instant().toString());
  }

  @Test
  void a_post_whose_event_site_was_not_read_yet_keeps_the_catalog_position() {
    server.route("/search", FixtureServer.Reply.ok(NO_RESULTS));
    server.route("/extract", FixtureServer.Reply.ok(POST_AND_SITE_EXTRACT));
    UndatedEvents undated =
        (since, after, limit) ->
            List.of(
                new UndatedEvents.Page(
                    POST_URL,
                    "Agentic Engineering Days Zurich 2026 | The AI Coding & Agents Conference | AI & ML Events",
                    position(1)));
    EventReader onlyTheSite =
        reader(title ->
            title.equals("Agentic Engineering Days Zurich 2026")
                ? readsConference(title)
                : Optional.empty());

    CollectedBatch batch = collector(3, onlyTheSite, undated).collect(null, null, 500);

    assertThat(server.requests)
        .as("two searches, one extract, the cap stops the site read")
        .hasSize(3);
    assertThat(batch.getHits()).isEmpty();
    assertThat(batch.getCheckpoint()).isNull();
    assertThat(QueryProgress.done(batch.getNextCursor()))
        .doesNotContain(LiveWebEventsCollector.UNDATED)
        .noneMatch(key -> key.startsWith(LiveWebEventsCollector.UNDATED_AFTER));
  }
}
