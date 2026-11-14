package ai.helper2.sources.liveweb.scout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.quota.QuotaCounter;
import ai.helper2.platform.quota.QuotaKeys;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CourseCollectorTest {

  static final String RESULTS =
      """
      {"results":[
        {"url":"https://huggingface.co/learn/agents-course/unit0/introduction?utm_source=x","title":"Welcome to the AI Agents Course",
         "content":"A free course on AI agents: build LLM agents and earn a certificate."},
        {"url":"https://huggingface.co/blog/new-model","title":"A new open LLM course of action",
         "content":"Blog post about a model release."},
        {"url":"https://huggingface.co/learn/cooking-course","title":"Cooking course","content":"Learn to cook pasta."},
        {"url":"https://huggingface.co/learn/nlp-overview","title":"Machine learning overview","content":"Transformers explained for LLM users."},
        {"url":"https://evil.example/learn/ai-course","title":"AI course","content":"An LLM course."}
      ]}\
      """;

  final FixtureServer server = new FixtureServer();
  final DiscoveryCollectorTest.MapCounter counter = new DiscoveryCollectorTest.MapCounter();
  final QuotaKeys keys = new QuotaKeys("", SourceFixtures.CLOCK);
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  @AfterEach
  void stop() {
    server.close();
  }

  CourseCollector collector(String token, List<String> platforms) {
    return collector(token, platforms, Resilience.NONE);
  }

  CourseCollector collector(String token, List<String> platforms, Resilience resilience) {
    return new CourseCollector(
        new SourceSettings(true, server.baseUrl(), token, Duration.ofSeconds(2), Duration.ZERO),
        TestHttp.restClientBuilder(
            Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
        SourceFixtures.CLOCK,
        resilience,
        new RunInterval(Duration.ofDays(7)),
        "new artificial intelligence course or certification",
        platforms,
        new ScoutLiveWebCredits(counter, keys, 40, meters));
  }

  @Test
  void eachSearchCountsItsCreditsOnTheScoutCapUnderItsOwnFeature() {
    server.route("/search", FixtureServer.Reply.ok("{\"usage\":{\"credits\":1},\"results\":[]}"));
    collector("tvly-test", List.of("coursera.org=Coursera", "deeplearning.ai=DeepLearning.AI"))
        .collect(null, null, 500);

    assertThat(counter.counts)
        .containsEntry(new QuotaCounter.Counter(keys.global(), QuotaKeys.CREDITS), 2L)
        .containsEntry(new QuotaCounter.Counter(keys.global(), QuotaKeys.SCOUT_CREDITS), 2L);
    assertThat(
            meters
                .get("helper2.live-web.credits")
                .tag("feature", "scout-courses")
                .counter()
                .count())
        .isEqualTo(2.0);
  }

  @Test
  void pastTheScoutsDailyCapItSearchesNothingAndKeepsThePassOpen() {
    counter.counts.put(new QuotaCounter.Counter(keys.global(), QuotaKeys.SCOUT_CREDITS), 40L);
    CollectedBatch batch =
        collector("tvly-test", List.of("coursera.org=Coursera")).collect(null, null, 500);

    assertThat(batch.getDeferred()).isTrue();
    assertThat(batch.getSkipReason()).contains("credit cap");
    assertThat(server.requests).isEmpty();
  }

  @Test
  void aCapReachedBetweenTwoPlatformsLeavesThePassOpenAtThePlatformNotSearched() {
    counter.counts.put(new QuotaCounter.Counter(keys.global(), QuotaKeys.SCOUT_CREDITS), 39L);
    server.route("/search", FixtureServer.Reply.ok(RESULTS));
    CollectedBatch batch =
        collector(
                "tvly-test",
                List.of("huggingface.co/learn=Hugging Face Learn", "coursera.org=Coursera"))
            .collect("2026-10-01T00:00:00Z", null, 500);

    assertThat(server.requests).hasSize(1);
    assertThat(batch.getCandidates()).isNotEmpty();
    assertThat(batch.getCheckpoint()).as("the pass is not complete").isNull();
    assertThat(batch.getNextCursor())
        .isEqualTo(QueryProgress.cursor(List.of("huggingface.co/learn")));
  }

  @Test
  void aPassLeftOpenSearchesOnlyThePlatformsNotDoneOverTheSameDaysAndThenMovesItsCheckpoint() {
    server.route("/search", FixtureServer.Reply.ok("{\"results\":[]}"));
    CollectedBatch batch =
        collector(
                "tvly-test",
                List.of("huggingface.co/learn=Hugging Face Learn", "coursera.org=Coursera"))
            .collect(
                "2026-10-01T00:00:00Z", QueryProgress.cursor(List.of("huggingface.co/learn")), 500);

    assertThat(server.requestBodies)
        .singleElement()
        .asString()
        .contains("\"include_domains\":[\"coursera.org\"]")
        .contains("\"start_date\":\"2026-10-01\"");
    assertThat(batch.getNextCursor()).isNull();
    assertThat(batch.getCheckpoint()).isEqualTo(SourceFixtures.CLOCK.instant().toString());
  }

  @Test
  void everyHttpAttemptOfARetriedSearchCountsItsCredits() {
    server.route("/search", FixtureServer.Reply.ok("{\"usage\":{\"credits\":1},\"results\":[]}"));
    collector("tvly-test", List.of("coursera.org=Coursera"), LiveWebNewsCollectorTest.TRIES_TWICE)
        .collect(null, null, 500);

    assertThat(counter.counts)
        .containsEntry(new QuotaCounter.Counter(keys.global(), QuotaKeys.SCOUT_CREDITS), 2L);
  }

  @Test
  void aSearchRefusedBeforeAnyHttpCallCountsNoCredit() {
    assertThatThrownBy(() ->
            collector(
                    "tvly-test", List.of("coursera.org=Coursera"), LiveWebNewsCollectorTest.REFUSES)
                .collect(null, null, 500))
        .isInstanceOf(IllegalStateException.class);

    assertThat(counter.counts).isEmpty();
    assertThat(server.requests).isEmpty();
  }

  @Test
  void searchesEachPlatformOnItsDomainAndProposesCoursePagesAsCandidatesNotHits() {
    server.route("/search", FixtureServer.Reply.ok(RESULTS));
    CollectedBatch batch =
        collector(
                "tvly-test",
                List.of("huggingface.co/learn=Hugging Face Learn", "coursera.org=Coursera"))
            .collect(null, null, 500);

    assertThat(server.requestBodies).hasSize(2);
    assertThat(server.requestBodies.get(0))
        .contains("\"include_domains\":[\"huggingface.co\"]")
        .contains("\"topic\":\"general\"")
        .contains("\"search_depth\":\"basic\"")
        .contains("\"time_range\":\"month\"")
        .doesNotContain("start_date");
    assertThat(server.requestBodies.get(1)).contains("\"include_domains\":[\"coursera.org\"]");
    assertThat(batch.getHits()).isEmpty();
    assertThat(batch.getCheckpoint()).isEqualTo(SourceFixtures.CLOCK.instant().toString());
    assertThat(batch.getCandidates())
        .extracting(c -> c.getUrl().getValue())
        .containsExactly("https://huggingface.co/learn/agents-course/unit0/introduction");
    assertThat(batch.getCandidates().getFirst().getPlatform()).isEqualTo("Hugging Face Learn");
    assertThat(batch.getCandidates().getFirst().getSnippet()).contains("certificate");
  }

  @Test
  void candidateTitlesLoseThePlatformSiteName() {
    server.route(
        "/search",
        FixtureServer.Reply.ok(
            """
            {"results":[{"url":"https://www.coursera.org/learn/generative-ai","title":"Generative AI for Everyone | Coursera",
              "content":"An AI course with a certificate."}]}\
            """));

    CollectedBatch batch =
        collector("tvly-test", List.of("coursera.org=Coursera")).collect(null, null, 500);

    assertThat(batch.getCandidates())
        .extracting(c -> c.getTitle())
        .containsExactly("Generative AI for Everyone");
  }

  @Test
  void laterRunsAskOnlyForPagesPublishedSinceThePreviousPass() {
    server.route("/search", FixtureServer.Reply.ok("{\"results\":[]}"));
    collector("tvly-test", List.of("deeplearning.ai=DeepLearning.AI"))
        .collect("2026-10-15T09:00:00Z", null, 500);
    assertThat(server.requestBodies.getFirst())
        .contains("\"start_date\":\"2026-10-15\"")
        .doesNotContain("time_range");
  }

  @Test
  void spendsNoCreditsBeforeTheWeeklyIntervalHasPassed() {
    CollectedBatch batch =
        collector("tvly-test", List.of("edx.org=edX")).collect("2026-10-20T09:00:00Z", null, 500);
    assertThat(batch.getNotDue()).isTrue();
    assertThat(server.requests).isEmpty();
    assertThat(batch.getCandidates()).isEmpty();
  }

  @Test
  void isDisabledWithoutAnApiKeyOrPlatformsAndDescribesCourses() {
    assertThat(collector("", List.of("edx.org=edX")).getDescriptor().getEnabled()).isFalse();
    assertThat(collector("tvly-test", List.of()).getDescriptor().getEnabled()).isFalse();
    CourseCollector enabled = collector("tvly-test", List.of("edx.org=edX"));
    assertThat(enabled.getDescriptor().getEnabled()).isTrue();
    assertThat(enabled.getDescriptor().getResourceTypes()).containsExactly(ResourceType.COURSE);
  }

  @Test
  void platformSpecsKeepHostPathAndName() {
    CourseCollector.Platform p =
        CourseCollector.Platform.parse(" www.AWS.amazon.com/certification/ = AWS ");
    assertThat(p)
        .isEqualTo(new CourseCollector.Platform("aws.amazon.com", "/certification", "AWS"));
    assertThat(
            p.covers(
                CanonicalUrl.of("https://aws.amazon.com/certification/certified-ai-practitioner/")))
        .isTrue();
    assertThat(p.covers(CanonicalUrl.of("https://aws.amazon.com/certificationx"))).isFalse();
    assertThat(p.covers(CanonicalUrl.of("https://aws.amazon.com/blogs/ai-course"))).isFalse();
    assertThat(
            CourseCollector.Platform.parse("nvidia.com")
                .covers(CanonicalUrl.of("https://learn.nvidia.com/courses/x")))
        .isTrue();
    assertThat(CourseCollector.Platform.parse("https://edx.org")).isNull();
    assertThat(CourseCollector.Platform.parse("localhost=Local")).isNull();
  }
}
