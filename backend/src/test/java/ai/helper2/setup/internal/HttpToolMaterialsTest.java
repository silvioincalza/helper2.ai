package ai.helper2.setup.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.setup.Material;
import ai.helper2.setup.MaterialKind;
import ai.helper2.setup.ToolRef;
import ai.helper2.setup.internal.RegistryMcpServerSpecsTest.MutableClock;
import ai.helper2.testing.FixtureServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The tool's own documents, read from fixed hosts with the right headers, kept for an hour within a size bound. */
class HttpToolMaterialsTest {

    private static final String README = "# acme-x\n\nInstall with `npm install acme-x`.\n";

    final FixtureServer server = new FixtureServer();
    final MutableClock clock = new MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpToolMaterials materials(int fetchesPerHour, long maxCacheChars) {
        return materials(fetchesPerHour, maxCacheChars, "gh-token");
    }

    private HttpToolMaterials materials(int fetchesPerHour, long maxCacheChars, String githubToken) {
        HourlyFetchBudget budget = new HourlyFetchBudget(fetchesPerHour, clock, null, Resilience.NONE,
            meters.counter("helper2.setup.fetches.refused"));
        return new HttpToolMaterials(ObservedHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP),
            new HttpToolMaterials.Hosts(server.baseUrl(), server.baseUrl() + "/jsdelivr", server.baseUrl() + "/hf", githubToken, "hf-token"),
            clock, budget, new HttpToolMaterials.CacheSettings(Duration.ofHours(1), maxCacheChars));
    }

    private static ToolRef ref(String repo, String npm, String hfModel) {
        return new ToolRef("Acme X", "MCP_SERVER", repo, npm, hfModel, null);
    }

    @Test
    void readsTheGithubReadmeRawWithTheSetupToken_andLinksTheRepositoryPage() {
        server.route("/repos/acme/x/readme", FixtureServer.Reply.ok(README));

        List<Material> docs = materials(100, 1_000_000).load(ref("acme/x", "acme-x", null));

        assertThat(docs).singleElement().satisfies(m -> {
            assertThat(m.getKind()).isEqualTo(MaterialKind.README);
            assertThat(m.getUrl()).isEqualTo(URI.create("https://github.com/acme/x"));
            assertThat(m.getText()).isEqualTo(README);
            assertThat(m.getRetrievedAt()).isEqualTo(clock.now);
        });
        Map<String, List<String>> headers = server.requestHeaders.get(0);
        assertThat(headers.get("Accept")).containsExactly("application/vnd.github.raw+json");
        assertThat(headers.get("Authorization")).containsExactly("Bearer gh-token");
        // The npm README is read only when GitHub gave nothing.
        assertThat(server.requests).containsExactly("/repos/acme/x/readme");
    }

    @Test
    void withoutASetupTokenTheGithubReadmeIsReadAnonymously_underTheSameHourlyBudget() {
        server.route("/repos/acme/", FixtureServer.Reply.ok(README));
        HttpToolMaterials materials = materials(1, 1_000_000, "");

        assertThat(materials.load(ref("acme/a", null, null))).hasSize(1);
        assertThat(server.requestHeaders.get(0)).doesNotContainKey("Authorization");
        assertThat(materials.load(ref("acme/b", null, null))).isEmpty();
        assertThat(meters.counter("helper2.setup.fetches.refused").count()).isEqualTo(1);
    }

    @Test
    void withoutAGithubReadmeTheNpmReadmeIsReadWithoutAToken_andAModelCardWithTheHubToken() {
        server.route("/jsdelivr/npm/acme-x/README.md", FixtureServer.Reply.ok(README));
        server.route("/hf/acme/model/raw/main/README.md", FixtureServer.Reply.ok("# Model card"));

        List<Material> docs = materials(100, 1_000_000).load(ref("acme/x", "acme-x", "acme/model"));

        assertThat(docs).extracting(Material::getKind).containsExactly(MaterialKind.PACKAGE_README, MaterialKind.MODEL_CARD);
        assertThat(docs.get(0).getUrl()).isEqualTo(URI.create("https://www.npmjs.com/package/acme-x"));
        assertThat(server.requests).containsExactly("/repos/acme/x/readme", "/jsdelivr/npm/acme-x/README.md",
            "/hf/acme/model/raw/main/README.md");
        assertThat(server.requestHeaders.get(1)).doesNotContainKey("Authorization");
        assertThat(server.requestHeaders.get(2).get("Authorization")).containsExactly("Bearer hf-token");
    }

    @Test
    void aLongDocumentIsCutAtTheByteCap() {
        server.route("/repos/acme/x/readme", FixtureServer.Reply.ok("a".repeat(HttpToolMaterials.MAX_BYTES + 10)));

        assertThat(materials(100, 10_000_000).load(ref("acme/x", null, null)))
            .singleElement().satisfies(m -> assertThat(m.getText()).hasSize(HttpToolMaterials.MAX_BYTES));
    }

    @Test
    void documentsAreKeptForTheTtl_thenReadAgain() {
        server.route("/repos/acme/x/readme", FixtureServer.Reply.ok(README));
        HttpToolMaterials materials = materials(100, 1_000_000);

        materials.load(ref("acme/x", null, null));
        materials.load(ref("acme/x", null, null));
        assertThat(server.requests).hasSize(1);

        clock.now = clock.now.plus(Duration.ofHours(1));
        materials.load(ref("acme/x", null, null));
        assertThat(server.requests).hasSize(2);
    }

    @Test
    void theCacheHoldsDocumentsUpToItsSize_notUpToACountOfResources() {
        server.route("/repos/acme/", FixtureServer.Reply.ok("b".repeat(4_000)));
        HttpToolMaterials materials = materials(100, 10_000);

        for (int i = 0; i < 10; i++) {
            materials.load(ref("acme/r" + i, null, null));
        }

        assertThat(materials.cachedChars()).isLessThanOrEqualTo(10_000);
    }

    @Test
    void overTheHourlyFetchBudgetNothingIsFetched_andTheEmptyAnswerIsNotKept() {
        server.route("/repos/acme/", FixtureServer.Reply.ok(README));
        HttpToolMaterials materials = materials(1, 1_000_000);

        assertThat(materials.load(ref("acme/a", null, null))).hasSize(1);
        assertThat(materials.load(ref("acme/b", null, null))).isEmpty();
        assertThat(server.requests).containsExactly("/repos/acme/a/readme");
        assertThat(meters.counter("helper2.setup.fetches.refused").count()).isEqualTo(1);

        clock.now = clock.now.plus(Duration.ofHours(1));
        assertThat(materials.load(ref("acme/b", null, null))).hasSize(1);
    }
}
