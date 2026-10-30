package ai.helper2.sources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.search.PopularityKind;
import ai.helper2.search.SourceFailure;
import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import ai.helper2.shared.Audience;
import ai.helper2.shared.DateStatus;
import ai.helper2.shared.ResourceType;
import ai.helper2.testing.FixtureServer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OtherSourcesTest {

    final FixtureServer server = new FixtureServer();

    @AfterEach
    void stop() {
        server.close();
    }

    SourceSettings settings() {
        return new SourceSettings(true, server.baseUrl(), "", Duration.ofSeconds(2), Duration.ofMinutes(30));
    }

    SourceQuery q(String text) {
        return new SourceQuery(text, List.of(), null, 10);
    }

    @Test
    void huggingFaceModelsMapIdDatesLicenseAndMetrics() {
        server.route("/api/models", FixtureServer.Reply.ok(FixtureServer.fixture("hf-models.json")));
        HuggingFaceModelsSource src = new HuggingFaceModelsSource(settings(), ObservedHttp.restClientBuilder(Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP), GitHubSourceTest.CLOCK);
        SourceHit h = src.search(q("qwen3")).getFirst();
        assertThat(h.getTitle()).isEqualTo("Qwen/Qwen3-0.6B");
        assertThat(h.getUrl().toString()).isEqualTo("https://huggingface.co/Qwen/Qwen3-0.6B");
        assertThat(h.getType()).isEqualTo(ResourceType.MODEL);
        assertThat(h.getLicense()).isEqualTo("Apache-2.0");
        assertThat(h.getPublished().getValue()).isEqualTo(Instant.parse("2025-04-27T03:40:08Z"));
        assertThat(h.getUpdated().getValue()).isEqualTo(Instant.parse("2025-07-26T03:46:27Z"));
        assertThat(h.getDescription()).as("listing has no description; never invented").isNull();
        assertThat(h.getPopularity()).extracting(p -> p.getKind()).containsExactlyInAnyOrder(PopularityKind.HF_LIKES, PopularityKind.HF_DOWNLOADS);
        assertThat(h.getTags()).contains("text-generation");
    }

    @Test
    void huggingFaceSpacesAreAppsForBothAudiences() {
        server.route("/api/spaces", FixtureServer.Reply.ok(FixtureServer.fixture("hf-spaces.json")));
        HuggingFaceSpacesSource src = new HuggingFaceSpacesSource(settings(), ObservedHttp.restClientBuilder(Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP), GitHubSourceTest.CLOCK);
        SourceHit h = src.search(q("translate")).getFirst();
        assertThat(h.getUrl().toString()).startsWith("https://huggingface.co/spaces/");
        assertThat(h.getType()).isEqualTo(ResourceType.PRODUCT);
        assertThat(h.getAudience()).isEqualTo(Audience.BOTH);
        assertThat(h.getUpdated().getStatus()).as("spaces listing has no lastModified").isEqualTo(DateStatus.UNKNOWN);
    }

    @Test
    void huggingFace429IsRateLimited() {
        server.route("/api/models", new FixtureServer.Reply(429, "{}", Map.of("Retry-After", "30"), 0));
        HuggingFaceModelsSource src = new HuggingFaceModelsSource(settings(), ObservedHttp.restClientBuilder(Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP), GitHubSourceTest.CLOCK);
        assertThatThrownBy(() -> src.search(q("x"))).isInstanceOfSatisfying(SourceFailure.RateLimited.class,
            e -> assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(30)));
    }

    @Test
    void npmMapsPackageRepositoryAndWeeklyDownloads() {
        server.route("/-/v1/search", FixtureServer.Reply.ok(FixtureServer.fixture("npm-search.json")));
        NpmSource src = new NpmSource(settings(), ObservedHttp.restClientBuilder(Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP), GitHubSourceTest.CLOCK);
        List<SourceHit> hits = src.search(q("mcp postgres"));
        SourceHit h = hits.getFirst();
        assertThat(h.getUrl().toString()).isEqualTo("https://www.npmjs.com/package/dsh-mcp-postgres");
        assertThat(h.getType()).isEqualTo(ResourceType.MCP_SERVER);
        assertThat(h.getUpdated().getValue()).as("npm 'date' is the latest publish").isEqualTo(Instant.parse("2026-10-07T13:19:44.227Z"));
        assertThat(h.getPublished().getStatus()).as("search API has no creation date").isEqualTo(DateStatus.UNKNOWN);
        assertThat(h.getPopularity()).singleElement().satisfies(p -> assertThat(p.getKind()).isEqualTo(PopularityKind.NPM_DOWNLOADS_WEEKLY));
    }

    @Test
    void aHubIdThatIsNoValidUrlPathIsSkippedAndTheOtherModelsAreKept() {
        server.route("/api/models", FixtureServer.Reply.ok("""
            [{"id":"acme/bad id","likes":1},{"id":"acme/good-model","likes":2}]"""));
        HuggingFaceModelsSource src = new HuggingFaceModelsSource(settings(), ObservedHttp.restClientBuilder(Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP), GitHubSourceTest.CLOCK);

        assertThat(src.search(q("model"))).extracting(SourceHit::getExternalId).containsExactly("acme/good-model");
    }

    /** A minimal hit, for tests of the plumbing around the mappers. */
    static SourceHit hit(String id, int rank) {
        String url = "https://example.org/" + id;
        return new SourceHit("test-source", id, java.net.URI.create(url), ai.helper2.shared.CanonicalUrl.of(url), null, id, null,
            ai.helper2.shared.ValueOrigin.SOURCE_PROVIDED, ResourceType.LIBRARY, Audience.BUILD_WITH_AI, null, java.util.Set.of(),
            java.util.Set.of(), null, null, java.util.Set.of(), null, ai.helper2.shared.DateFact.unknown(),
            ai.helper2.shared.DateFact.unknown(), List.of(), GitHubSourceTest.CLOCK.instant(), rank);
    }
}
