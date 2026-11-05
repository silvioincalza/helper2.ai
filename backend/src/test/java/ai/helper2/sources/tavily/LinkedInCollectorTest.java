package ai.helper2.sources.tavily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.quota.QuotaCounter;
import ai.helper2.platform.quota.QuotaKeys;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.shared.CanonicalUrl;
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
import org.junit.jupiter.api.Test;

class LinkedInCollectorTest {

  static final String RESULTS =
      """
      {"results":[
        {"url":"https://www.linkedin.com/pulse/agents-production-what-we-learned-jane-doe-abc1f?trk=public","title":"Agents in production: what we learned | Jane Doe | 12 comments - LinkedIn",
         "content":"We moved three LLM agents to production. Here is what broke first."},
        {"url":"https://www.linkedin.com/posts/jane-doe_nemotron-activity-7390000000000000000-AbCd","title":"Jane Doe on LinkedIn: NVIDIA released Nemotron 3 Nano today #ai",
         "content":"NVIDIA released Nemotron 3 Nano today, an open model for agents."},
        {"url":"https://ch.linkedin.com/posts/john-roe_activity-7390000000000000001-XyZw","title":"John Roe's Post",
         "content":"Our open-source MCP server for Postgres is out. It lets agents query a database safely, with read-only roles and row limits."},
        {"url":"https://www.linkedin.com/company/acme-ai/","title":"Acme AI | LinkedIn",
         "content":"Acme AI builds a voice agent platform for call centres."},
        {"url":"https://www.linkedin.com/showcase/acme-voice/about/","title":"Acme Voice | LinkedIn",
         "content":"Acme Voice: AI receptionists."},
        {"url":"https://www.linkedin.com/events/genaimeetupzurich7391234567890/","title":"GenAI Meetup Zurich | LinkedIn",
         "content":"A meetup on generative AI in Zurich."},
        {"url":"https://www.linkedin.com/in/jane-doe/","title":"Jane Doe - AI engineer - Acme | LinkedIn",
         "content":"AI engineer at Acme."},
        {"url":"https://www.linkedin.com/jobs/view/ml-engineer-4012345678","title":"ML Engineer - Acme","content":"Job."},
        {"url":"https://www.linkedin.com/learning/introduction-to-ai","title":"Introduction to AI","content":"Course."},
        {"url":"https://www.linkedin.com/company/acme-ai/jobs/","title":"Jobs at Acme AI","content":"Jobs."},
        {"url":"https://evil.example/pulse/ai-news","title":"AI news","content":"Not LinkedIn."}
      ]}\
      """;

  final FixtureServer server = new FixtureServer();
  final TavilyDiscoveryCollectorTest.MapCounter counter =
      new TavilyDiscoveryCollectorTest.MapCounter();
  final QuotaKeys keys = new QuotaKeys("", SourceFixtures.CLOCK);
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  @AfterEach
  void stop() {
    server.close();
  }

  LinkedInCollector collector(String token, List<String> queries) {
    return new LinkedInCollector(
        new SourceSettings(true, server.baseUrl(), token, Duration.ofSeconds(2), Duration.ZERO),
        ObservedHttp.restClientBuilder(
            Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
        SourceFixtures.CLOCK,
        Resilience.NONE,
        new RunInterval(Duration.ofDays(7)),
        queries,
        new ScoutTavilyCredits(counter, keys, 40, meters));
  }

  @Test
  void searchesOnlyLinkedInAndProposesEveryKeptPageAsACandidateNotAHit() {
    server.route("/search", FixtureServer.Reply.ok(RESULTS));
    CollectedBatch batch =
        collector("tvly-test", List.of("new AI tool launched", "AI conference"))
            .collect(null, null, 500);

    assertThat(server.requestBodies).hasSize(2);
    assertThat(server.requestBodies.getFirst())
        .contains("\"query\":\"new AI tool launched\"")
        .contains("\"include_domains\":[\"linkedin.com\"]")
        .contains("\"topic\":\"general\"")
        .contains("\"search_depth\":\"basic\"")
        .contains("\"max_results\":10")
        .contains("\"include_usage\":true")
        .contains("\"include_raw_content\":false")
        .contains("\"include_answer\":false")
        .contains("\"time_range\":\"month\"")
        .doesNotContain("start_date");
    // Nothing goes to the catalog: the batch has no hits and the pass is complete.
    assertThat(batch.getHits()).isEmpty();
    assertThat(batch.getCheckpoint()).isEqualTo(SourceFixtures.CLOCK.instant().toString());
    // Both searches return the same fixture: each page once. Dropped: the profile, the job, the
    // learning page, the company's jobs page and the other host.
    assertThat(batch.getCandidates())
        .extracting(c -> c.getUrl().getValue(), Candidate::getType, Candidate::getPlatform)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "https://linkedin.com/pulse/agents-production-what-we-learned-jane-doe-abc1f",
                ResourceType.ARTICLE,
                "LinkedIn article"),
            org.assertj.core.groups.Tuple.tuple(
                "https://linkedin.com/posts/jane-doe_nemotron-activity-7390000000000000000-AbCd",
                ResourceType.NEWS,
                "LinkedIn post"),
            org.assertj.core.groups.Tuple.tuple(
                "https://ch.linkedin.com/posts/john-roe_activity-7390000000000000001-XyZw",
                ResourceType.NEWS,
                "LinkedIn post"),
            org.assertj.core.groups.Tuple.tuple(
                "https://linkedin.com/company/acme-ai",
                ResourceType.PRODUCT,
                "LinkedIn company page"),
            org.assertj.core.groups.Tuple.tuple(
                "https://linkedin.com/showcase/acme-voice/about",
                ResourceType.PRODUCT,
                "LinkedIn company page"),
            org.assertj.core.groups.Tuple.tuple(
                "https://linkedin.com/events/genaimeetupzurich7391234567890",
                ResourceType.EVENT,
                "LinkedIn event"));
    assertThat(batch.getCandidates())
        .allSatisfy(c -> assertThat(c.getTags()).containsExactly("linkedin"));
  }

  @Test
  void titlesNameThePageNotItsAuthorOrTheSite() {
    server.route("/search", FixtureServer.Reply.ok(RESULTS));
    CollectedBatch batch = collector("tvly-test", List.of("q")).collect(null, null, 500);

    assertThat(batch.getCandidates())
        .extracting(Candidate::getTitle)
        .containsExactly(
            "Agents in production: what we learned",
            "NVIDIA released Nemotron 3 Nano today #ai",
            // The title names only the author: the post's text, cut at a word.
            "Our open-source MCP server for Postgres is out. It lets agents query a database safely,"
                + " with read-only roles and row…",
            "Acme AI",
            "Acme Voice",
            "GenAI Meetup Zurich");
    assertThat(batch.getCandidates())
        .extracting(Candidate::getTitle)
        .noneMatch(t -> t.contains("Jane Doe") || t.contains("John Roe") || t.contains("LinkedIn"));
  }

  @Test
  void anArticleWhoseTitleNamesOnlyItsAuthorIsDropped() {
    assertThat(
            LinkedInCollector.title(LinkedInCollector.Kind.ARTICLE, "Jane Doe's Article", "text"))
        .isNull();
    assertThat(LinkedInCollector.title(LinkedInCollector.Kind.POST, "Jane Doe's Post", null))
        .isNull();
  }

  @Test
  void excerptsAreShortAndCutAtAWord() {
    String longText = "word ".repeat(100);
    String snippet = LinkedInCollector.snippet(longText);
    assertThat(snippet).hasSizeLessThanOrEqualTo(LinkedInCollector.MAX_SNIPPET).endsWith("word…");
    assertThat(LinkedInCollector.snippet("  ")).isNull();
  }

  @Test
  void thePathDecidesTheKind() {
    assertThat(kind("https://www.linkedin.com/pulse/x")).isEqualTo(LinkedInCollector.Kind.ARTICLE);
    assertThat(kind("https://www.linkedin.com/feed/update/urn:li:activity:7390000000000000000"))
        .isEqualTo(LinkedInCollector.Kind.POST);
    assertThat(kind("https://www.linkedin.com/company/acme/posts/x"))
        .isEqualTo(LinkedInCollector.Kind.POST);
    assertThat(kind("https://www.linkedin.com/company/acme/about/"))
        .isEqualTo(LinkedInCollector.Kind.COMPANY);
    assertThat(kind("https://www.linkedin.com/events/x/")).isEqualTo(LinkedInCollector.Kind.EVENT);
    assertThat(kind("https://www.linkedin.com/in/jane-doe")).isNull();
    assertThat(kind("https://www.linkedin.com/school/eth-zurich")).isNull();
    assertThat(kind("https://www.linkedin.com/pulse/topics/ai")).isNull();
    assertThat(kind("https://notlinkedin.com/pulse/x")).isNull();
  }

  @Test
  void laterRunsAskOnlyForPagesPublishedSinceThePreviousPass() {
    server.route("/search", FixtureServer.Reply.ok("{\"results\":[]}"));
    collector("tvly-test", List.of("AI hackathon")).collect("2026-10-15T09:00:00Z", null, 500);
    assertThat(server.requestBodies.getFirst())
        .contains("\"start_date\":\"2026-10-15\"")
        .doesNotContain("time_range");
  }

  @Test
  void spendsNoCreditsBeforeTheWeeklyIntervalHasPassedAndSaysWhenItWill() {
    CollectedBatch batch =
        collector("tvly-test", List.of("AI hackathon")).collect("2026-10-20T09:00:00Z", null, 500);
    assertThat(batch.getNotDue()).isTrue();
    assertThat(batch.getSkipReason()).isEqualTo("Not due yet: next pass on 2026-10-27");
    assertThat(server.requests).isEmpty();
  }

  @Test
  void eachSearchCountsItsCreditsOnTheScoutCapUnderItsOwnFeature() {
    server.route("/search", FixtureServer.Reply.ok("{\"usage\":{\"credits\":1},\"results\":[]}"));
    collector("tvly-test", List.of("AI conference", "AI hackathon")).collect(null, null, 500);

    assertThat(counter.counts)
        .containsEntry(new QuotaCounter.Counter(keys.global(), QuotaKeys.CREDITS), 2L)
        .containsEntry(new QuotaCounter.Counter(keys.global(), QuotaKeys.SCOUT_CREDITS), 2L);
    assertThat(
            meters.get("helper2.tavily.credits").tag("feature", "scout-linkedin").counter().count())
        .isEqualTo(2.0);
  }

  @Test
  void pastTheScoutsDailyCapItSkipsWithoutSearching() {
    counter.counts.put(new QuotaCounter.Counter(keys.global(), QuotaKeys.SCOUT_CREDITS), 40L);
    CollectedBatch batch =
        collector("tvly-test", List.of("AI conference")).collect(null, null, 500);

    assertThat(batch.getNotDue()).isTrue();
    assertThat(batch.getSkipReason()).contains("credit cap");
    assertThat(server.requests).isEmpty();
  }

  @Test
  void thePassFailsWhenHalfOrMoreOfItsSearchesFail() {
    server.route("/search", new FixtureServer.Reply(500, "{}", Map.of(), 0));
    assertThatThrownBy(() ->
            collector("tvly-test", List.of("AI conference", "AI hackathon"))
                .collect(null, null, 500))
        .isInstanceOf(RuntimeException.class);
    assertThat(server.requestBodies).hasSize(2);
  }

  @Test
  void isDisabledWithoutAnApiKeyOrQueriesAndDeclaresWhatItCanPropose() {
    assertThat(collector("", List.of("q")).getDescriptor().getEnabled()).isFalse();
    assertThat(collector("tvly-test", List.of(" ")).getDescriptor().getEnabled()).isFalse();
    LinkedInCollector enabled = collector("tvly-test", List.of("q"));
    assertThat(enabled.getDescriptor().getEnabled()).isTrue();
    assertThat(enabled.getDescriptor().getResourceTypes())
        .containsExactlyInAnyOrder(
            ResourceType.ARTICLE, ResourceType.NEWS, ResourceType.PRODUCT, ResourceType.EVENT);
  }

  private static LinkedInCollector.Kind kind(String url) {
    return LinkedInCollector.kind(CanonicalUrl.of(url));
  }
}
