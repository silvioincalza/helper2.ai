package ai.helper2.sources.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.ExtraText;
import ai.helper2.scout.GapKind;
import ai.helper2.scout.GapTarget;
import ai.helper2.search.SourceFailure;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReadmeEvidenceTest {

    final FixtureServer server = new FixtureServer();

    @AfterEach
    void stop() {
        server.close();
    }

    private ReadmeEvidence evidence() {
        SourceSettings settings = new SourceSettings(true, server.baseUrl(), "token", Duration.ofSeconds(2), Duration.ZERO);
        return ReadmeEvidence.of(settings, settings, ObservedHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP),
            ObservedHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP), Resilience.NONE, SourceFixtures.CLOCK);
    }

    private static GapTarget target(GapKind kind) {
        return new GapTarget(kind, "acme", "tool", UUID.randomUUID());
    }

    @Test
    void readsTheReadmeAndTheLicenceGitHubStates() {
        server.route("/repos/acme/tool/readme", FixtureServer.Reply.ok("# Tool\nMIT licensed."));
        server.route("/repos/acme/tool", FixtureServer.Reply.ok("{\"license\":{\"spdx_id\":\"MIT\"}}"));

        ExtraText text = evidence().fetch(target(GapKind.GITHUB_README));

        assertThat(text).isNotNull();
        assertThat(text.getRaw()).contains("MIT licensed");
        assertThat(text.getLicense()).isEqualTo("MIT");
        assertThat(text.getSourceUrl()).hasToString("https://github.com/acme/tool/blob/HEAD/README.md");
        assertThat(server.requestHeaders.getFirst()).containsKey("Accept");
        assertThat(server.requestHeaders.getFirst().get("Accept").getFirst()).contains("raw");
    }

    @Test
    void noAssertionIsNoLicence() {
        server.route("/repos/acme/tool/readme", FixtureServer.Reply.ok("# Tool"));
        server.route("/repos/acme/tool", FixtureServer.Reply.ok("{\"license\":{\"spdx_id\":\"NOASSERTION\"}}"));

        assertThat(evidence().fetch(target(GapKind.GITHUB_README)).getLicense()).isNull();
    }

    @Test
    void aMissingReadmeIsNoText() {
        server.route("/repos/acme/tool/readme", new FixtureServer.Reply(404, "{}", Map.of(), 0));

        assertThat(evidence().fetch(target(GapKind.GITHUB_README))).isNull();
        assertThat(server.requests).hasSize(1);
    }

    @Test
    void aServerErrorIsAFailure() {
        server.route("/repos/acme/tool/readme", new FixtureServer.Reply(500, "{}", Map.of(), 0));

        assertThatThrownBy(() -> evidence().fetch(target(GapKind.GITHUB_README))).isInstanceOf(SourceFailure.class);
    }

    @Test
    void readsTheModelCardAndItsLicence() {
        server.route("/acme/tool/raw/main/README.md", FixtureServer.Reply.ok("---\nlicense: apache-2.0\ntags: [asr]\n---\n# Tool\n"));

        ExtraText text = evidence().fetch(target(GapKind.HF_MODEL_CARD));

        assertThat(text.getLicense()).isEqualTo("apache-2.0");
        assertThat(text.getSourceUrl()).hasToString("https://huggingface.co/acme/tool/blob/main/README.md");
    }

    @Test
    void cardLicenceOnlyFromFrontMatter() {
        assertThat(ReadmeEvidence.cardLicense("# Tool\nlicense: mit\n")).isNull();
        assertThat(ReadmeEvidence.cardLicense("---\nlicense: other\n---\n")).isNull();
        assertThat(ReadmeEvidence.cardLicense("---\nlicense: \"llama3.1\"\n---\n")).isEqualTo("llama3.1");
    }
}
