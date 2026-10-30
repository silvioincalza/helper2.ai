package ai.helper2.sources.tavily;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CourseCollectorTest {

    static final String RESULTS = """
        {"results":[
          {"url":"https://huggingface.co/learn/agents-course/unit0/introduction?utm_source=x","title":"Welcome to the AI Agents Course",
           "content":"A free course on AI agents: build LLM agents and earn a certificate."},
          {"url":"https://huggingface.co/blog/new-model","title":"A new open LLM course of action",
           "content":"Blog post about a model release."},
          {"url":"https://huggingface.co/learn/cooking-course","title":"Cooking course","content":"Learn to cook pasta."},
          {"url":"https://huggingface.co/learn/nlp-overview","title":"Machine learning overview","content":"Transformers explained for LLM users."},
          {"url":"https://evil.example/learn/ai-course","title":"AI course","content":"An LLM course."}
        ]}""";

    final FixtureServer server = new FixtureServer();

    @AfterEach
    void stop() {
        server.close();
    }

    CourseCollector collector(String token, List<String> platforms) {
        return new CourseCollector(new SourceSettings(true, server.baseUrl(), token, Duration.ofSeconds(2), Duration.ZERO),
            ObservedHttp.restClientBuilder(Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP), SourceFixtures.CLOCK, Resilience.NONE,
            new RunInterval(Duration.ofDays(7)), "new artificial intelligence course or certification", platforms);
    }

    @Test
    void searchesEachPlatformOnItsDomainAndProposesCoursePagesAsCandidatesNotHits() {
        server.route("/search", FixtureServer.Reply.ok(RESULTS));
        CollectedBatch batch = collector("tvly-test", List.of("huggingface.co/learn=Hugging Face Learn", "coursera.org=Coursera"))
            .collect(null, null, 500);

        assertThat(server.requestBodies).hasSize(2);
        assertThat(server.requestBodies.get(0)).contains("\"include_domains\":[\"huggingface.co\"]").contains("\"topic\":\"general\"")
            .contains("\"search_depth\":\"basic\"").contains("\"time_range\":\"month\"").doesNotContain("start_date");
        assertThat(server.requestBodies.get(1)).contains("\"include_domains\":[\"coursera.org\"]");
        // Nothing goes to the catalog: the batch is empty and the pass is complete.
        assertThat(batch.getHits()).isEmpty();
        assertThat(batch.getCheckpoint()).isEqualTo(SourceFixtures.CLOCK.instant().toString());
        // Kept: on huggingface.co/learn, about AI, reads like a course. Dropped: the blog (wrong path), the cooking
        // course (not AI), the overview (not a course page) and the other host. Coursera's run returns the same
        // fixture, all off its domain.
        assertThat(batch.getCandidates()).extracting(c -> c.getUrl().getValue())
            .containsExactly("https://huggingface.co/learn/agents-course/unit0/introduction");
        assertThat(batch.getCandidates().getFirst().getPlatform()).isEqualTo("Hugging Face Learn");
        assertThat(batch.getCandidates().getFirst().getSnippet()).contains("certificate");
    }

    @Test
    void laterRunsAskOnlyForPagesPublishedSinceThePreviousPass() {
        server.route("/search", FixtureServer.Reply.ok("{\"results\":[]}"));
        collector("tvly-test", List.of("deeplearning.ai=DeepLearning.AI")).collect("2026-10-15T09:00:00Z", null, 500);
        assertThat(server.requestBodies.getFirst()).contains("\"start_date\":\"2026-10-15\"").doesNotContain("time_range");
    }

    @Test
    void spendsNoCreditsBeforeTheWeeklyIntervalHasPassed() {
        CollectedBatch batch = collector("tvly-test", List.of("edx.org=edX")).collect("2026-10-20T09:00:00Z", null, 500);
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
        CourseCollector.Platform p = CourseCollector.Platform.parse(" www.AWS.amazon.com/certification/ = AWS ");
        assertThat(p).isEqualTo(new CourseCollector.Platform("aws.amazon.com", "/certification", "AWS"));
        assertThat(p.covers(CanonicalUrl.of("https://aws.amazon.com/certification/certified-ai-practitioner/"))).isTrue();
        assertThat(p.covers(CanonicalUrl.of("https://aws.amazon.com/certificationx"))).isFalse();
        assertThat(p.covers(CanonicalUrl.of("https://aws.amazon.com/blogs/ai-course"))).isFalse();
        assertThat(CourseCollector.Platform.parse("nvidia.com").covers(CanonicalUrl.of("https://learn.nvidia.com/courses/x"))).isTrue();
        assertThat(CourseCollector.Platform.parse("https://edx.org")).isNull();
        assertThat(CourseCollector.Platform.parse("localhost=Local")).isNull();
    }
}
