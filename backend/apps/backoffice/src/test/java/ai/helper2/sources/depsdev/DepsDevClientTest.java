package ai.helper2.sources.depsdev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.search.SourceFailure;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DepsDevClientTest {

  static final String PROJECT_VERSIONS =
      """
      {"versions":[
        {"versionKey":{"system":"NPM","name":"openai","version":"7.54.0"},
         "relationType":"SOURCE_REPO","relationProvenance":"SLSA_ATTESTATION"},
        {"versionKey":{"system":"GO","name":"github.com/openai/openai-node","version":"v7.54.0"},
         "relationType":"SOURCE_REPO","relationProvenance":"GO_ORIGIN"},
        {"versionKey":{"system":"NPM","name":"openai-issue","version":"1.0.0"},
         "relationType":"ISSUE_TRACKER","relationProvenance":"UNVERIFIED_METADATA"},
        {"versionKey":{"system":"CONDA","name":"openai","version":"1.0.0"},
         "relationType":"SOURCE_REPO","relationProvenance":"UNVERIFIED_METADATA"}
      ]}\
      """;

  static final String PACKAGE =
      """
      {"packageKey":{"system":"NPM","name":"@anthropic-ai/sdk"},"versions":[
        {"versionKey":{"version":"1.5.0"},"isDefault":false},
        {"versionKey":{"version":"1.6.0"},"isDefault":true},
        {"versionKey":{"version":"2.0.0-beta.1"},"isDefault":false}
      ]}\
      """;

  static final String VERSION =
      """
      {"versionKey":{"system":"NPM","name":"@anthropic-ai/sdk","version":"1.6.0"},
       "publishedAt":"2026-11-05T19:05:25Z","licenses":["Apache-2.0"],
       "relatedProjects":[
         {"projectKey":{"id":"github.com/anthropics/anthropic-sdk-typescript"},
          "relationProvenance":"UNVERIFIED_METADATA","relationType":"SOURCE_REPO"}]}\
      """;

  final FixtureServer server = new FixtureServer();

  final DepsDevClient client =
      new DepsDevClient(
          TestHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP).build(),
          server.baseUrl(),
          SourceFixtures.CLOCK,
          Resilience.NONE,
          "collector-deps-dev");

  @AfterEach
  void stop() {
    server.close();
  }

  @Test
  void readsTheSourceRepoVersionsOfTheKnownRegistries() {
    server.route(
        "/v3/projects/github.com%2Fopenai%2Fopenai-node:packageversions",
        FixtureServer.Reply.ok(PROJECT_VERSIONS));

    List<PackageVersion> versions = client.projectVersions("openai/openai-node");

    assertThat(versions)
        .containsExactly(
            new PackageVersion(Ecosystem.NPM, "openai", "SLSA_ATTESTATION"),
            new PackageVersion(Ecosystem.GO, "github.com/openai/openai-node", "GO_ORIGIN"));
  }

  @Test
  void theProjectKeyIsLowerCaseAndEncodedAsOnePathSegment() {
    server.route("/v3/projects/", FixtureServer.Reply.ok("{\"versions\":[]}"));

    client.projectVersions("0xPlaygrounds/rig");

    assertThat(server.requests)
        .containsExactly("/v3/projects/github.com%2F0xplaygrounds%2Frig:packageversions");
  }

  @Test
  void theLatestPackageIsItsDefaultVersionWithDateLicenceAndSourceRepo() {
    server.route("/v3/systems/NPM/packages/%40anthropic-ai%2Fsdk", FixtureServer.Reply.ok(PACKAGE));
    server.route(
        "/v3/systems/NPM/packages/%40anthropic-ai%2Fsdk/versions/1.6.0",
        FixtureServer.Reply.ok(VERSION));

    RegistryPackage p = client.latest(Ecosystem.NPM, "@anthropic-ai/sdk").orElseThrow();

    assertThat(p.version()).isEqualTo("1.6.0");
    assertThat(p.publishedAt()).isEqualTo(Instant.parse("2026-11-05T19:05:25Z"));
    assertThat(p.spdxLicense()).isEqualTo("Apache-2.0");
    assertThat(p.comesFrom("anthropics/anthropic-sdk-typescript")).isTrue();
  }

  @Test
  void anUnknownPackageIsEmptyNotAFailureAndItsPlainTextBodyIsNotRead() {
    server.route(
        "/v3/systems/PYPI/packages/nope",
        new FixtureServer.Reply(404, "Package not found", Map.of(), 0));

    Optional<RegistryPackage> p = client.latest(Ecosystem.PYPI, "nope");

    assertThat(p).isEmpty();
  }

  @Test
  void anUnknownProjectHasNoVersions() {
    server.route("/v3/projects/", new FixtureServer.Reply(404, "project not found", Map.of(), 0));

    assertThat(client.projectVersions("acme/nothing")).isEmpty();
  }

  @Test
  void aServerErrorIsAFailure() {
    server.route("/v3/systems/NPM/packages/x", new FixtureServer.Reply(500, "oops", Map.of(), 0));

    assertThatThrownBy(() -> client.latest(Ecosystem.NPM, "x")).isInstanceOf(SourceFailure.class);
  }
}
