package ai.helper2.sources.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.cache.SharedCache;
import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.ExtraText;
import ai.helper2.scout.GapKind;
import ai.helper2.scout.GapTarget;
import ai.helper2.scout.ReadmeCheck;
import ai.helper2.search.SourceFailure;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.HashMap;
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

  /** The shared cache in memory: what the adapter keeps, by key. */
  static final class Revisions implements SharedCache {
    final Map<String, String> kept = new HashMap<>();
    final Map<String, Duration> ttls = new HashMap<>();

    @Override
    public String name() {
      return ReadmeEvidence.REVISION_CACHE;
    }

    @Override
    public String get(String key) {
      return kept.get(key);
    }

    @Override
    public void put(String key, String value, Duration ttl) {
      kept.put(key, value);
      ttls.put(key, ttl);
    }
  }

  final Revisions revisions = new Revisions();

  private ReadmeEvidence evidence() {
    SourceSettings settings =
        new SourceSettings(true, server.baseUrl(), "token", Duration.ofSeconds(2), Duration.ZERO);
    return ReadmeEvidence.of(
        settings,
        settings,
        ObservedHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP),
        ObservedHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP),
        Resilience.NONE,
        SourceFixtures.CLOCK,
        revisions);
  }

  private static FixtureServer.Reply readmeWithEtag(String body, String etag) {
    return new FixtureServer.Reply(200, body, Map.of("ETag", etag), 0);
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
    assertThat(text.getSourceUrl()).hasToString("https://github.com/acme/tool#readme");
    assertThat(server.requestHeaders.getFirst()).containsKey("Accept");
    assertThat(server.requestHeaders.getFirst().get("Accept").getFirst()).contains("raw");
  }

  @Test
  void noGitHubCallsWithoutAToken() {
    SourceSettings anonymous =
        new SourceSettings(true, server.baseUrl(), null, Duration.ofSeconds(2), Duration.ZERO);
    ReadmeEvidence evidence =
        ReadmeEvidence.of(
            anonymous,
            anonymous,
            ObservedHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP),
            ObservedHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP),
            Resilience.NONE,
            SourceFixtures.CLOCK,
            revisions);

    assertThat(evidence.fetch(target(GapKind.GITHUB_README))).isNull();
    assertThat(evidence.check(target(GapKind.GITHUB_README))).isNull();
    assertThat(server.requests).isEmpty();
  }

  @Test
  void noAssertionIsNoLicence() {
    server.route("/repos/acme/tool/readme", FixtureServer.Reply.ok("# Tool"));
    server.route(
        "/repos/acme/tool", FixtureServer.Reply.ok("{\"license\":{\"spdx_id\":\"NOASSERTION\"}}"));

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

    assertThatThrownBy(() -> evidence().fetch(target(GapKind.GITHUB_README)))
        .isInstanceOf(SourceFailure.class);
  }

  @Test
  void aRedirectThatWasNotFollowedIsAFailureNotText() {
    server.route(
        "/acme/tool/raw/main/README.md",
        new FixtureServer.Reply(
            302, "<html>moved</html>", Map.of("Location", "https://other-host.example/x"), 0));

    assertThatThrownBy(() -> evidence().fetch(target(GapKind.HF_MODEL_CARD)))
        .isInstanceOf(SourceFailure.class);
  }

  @Test
  void readsTheModelCardAndItsLicence() {
    server.route(
        "/acme/tool/raw/main/README.md",
        FixtureServer.Reply.ok("---\nlicense: apache-2.0\ntags: [asr]\n---\n# Tool\n"));

    ExtraText text = evidence().fetch(target(GapKind.HF_MODEL_CARD));

    assertThat(text.getLicense()).isEqualTo("apache-2.0");
    assertThat(text.getSourceUrl())
        .hasToString("https://huggingface.co/acme/tool/blob/main/README.md");
  }

  @Test
  void aFirstCheckReadsTheReadmeAndKeepsOnlyItsEtagAndHash() {
    server.route("/repos/acme/tool/readme", readmeWithEtag("# Tool\nMIT licensed.", "\"abc\""));
    server.route("/repos/acme/tool", FixtureServer.Reply.ok("{\"license\":{\"spdx_id\":\"MIT\"}}"));

    ReadmeCheck check = evidence().check(target(GapKind.GITHUB_README));

    assertThat(check.getNotModified()).isFalse();
    assertThat(check.getText().getRaw()).contains("MIT licensed");
    assertThat(check.getText().getLicense()).isEqualTo("MIT");
    assertThat(check.getRevision()).isEqualTo(ReadmeEvidence.hash("# Tool\nMIT licensed."));
    assertThat(server.requestHeaders.getFirst()).doesNotContainKey("If-none-match");
    assertThat(revisions.kept.values())
        .singleElement()
        .isEqualTo("\"abc\"\n" + check.getRevision())
        .asString()
        .doesNotContain("MIT licensed");
    assertThat(revisions.ttls.values()).containsExactly(Duration.ofDays(30));
  }

  @Test
  void aNotModifiedAnswerUsesTheKeptRevisionAndReadsNothingMore() {
    server.route("/repos/acme/tool/readme", readmeWithEtag("# Tool\nMIT licensed.", "\"abc\""));
    server.route("/repos/acme/tool", FixtureServer.Reply.ok("{}"));
    ReadmeEvidence evidence = evidence();
    String revision = evidence.check(target(GapKind.GITHUB_README)).getRevision();
    server.route("/repos/acme/tool/readme", new FixtureServer.Reply(304, "", Map.of(), 0));
    server.requests.clear();
    server.requestHeaders.clear();

    ReadmeCheck check = evidence.check(target(GapKind.GITHUB_README));

    assertThat(check.getNotModified()).isTrue();
    assertThat(check.getText()).isNull();
    assertThat(check.getRevision()).isEqualTo(revision);
    assertThat(server.requests).containsExactly("/repos/acme/tool/readme");
    assertThat(server.requestHeaders.getFirst().get("If-none-match")).containsExactly("\"abc\"");
  }

  @Test
  void aFetchAlwaysReadsInFull() {
    server.route("/repos/acme/tool/readme", readmeWithEtag("# Tool", "\"abc\""));
    server.route("/repos/acme/tool", FixtureServer.Reply.ok("{}"));
    ReadmeEvidence evidence = evidence();
    evidence.check(target(GapKind.GITHUB_README));
    server.requestHeaders.clear();

    ExtraText text = evidence.fetch(target(GapKind.GITHUB_README));

    assertThat(text.getRaw()).isEqualTo("# Tool");
    assertThat(text.getRevision()).isEqualTo(ReadmeEvidence.hash("# Tool"));
    assertThat(server.requestHeaders.getFirst()).doesNotContainKey("If-none-match");
  }

  @Test
  void aRateLimitIsAFailure() {
    server.route(
        "/repos/acme/tool/readme",
        new FixtureServer.Reply(403, "{}", Map.of("x-ratelimit-remaining", "0"), 0));

    assertThatThrownBy(() -> evidence().check(target(GapKind.GITHUB_README)))
        .isInstanceOf(SourceFailure.RateLimited.class);
  }

  @Test
  void aModelCardCheckIsAFullRead() {
    server.route("/acme/tool/raw/main/README.md", FixtureServer.Reply.ok("# Card"));

    ReadmeCheck check = evidence().check(target(GapKind.HF_MODEL_CARD));

    assertThat(check.getText().getRaw()).isEqualTo("# Card");
    assertThat(revisions.kept).isEmpty();
  }

  @Test
  void cardLicenceOnlyFromFrontMatter() {
    assertThat(ReadmeEvidence.cardLicense("# Tool\nlicense: mit\n")).isNull();
    assertThat(ReadmeEvidence.cardLicense("---\nlicense: other\n---\n")).isNull();
    assertThat(ReadmeEvidence.cardLicense("---\nlicense: \"llama3.1\"\n---\n"))
        .isEqualTo("llama3.1");
    assertThat(
            ReadmeEvidence.cardLicense(
                "---\nbase_model:\n  license: mit\nlicense: apache-2.0\n---\n"))
        .isEqualTo("apache-2.0");
  }
}
