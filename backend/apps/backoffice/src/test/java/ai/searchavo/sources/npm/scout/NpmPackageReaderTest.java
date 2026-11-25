package ai.searchavo.sources.npm.scout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.search.SourceFailure;
import ai.searchavo.search.SourceHit;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.sources.SourceFixtures;
import ai.searchavo.testing.FixtureServer;
import ai.searchavo.testing.TestHttp;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class NpmPackageReaderTest {

  static final String SCOPED =
      """
      {"name":"@langchain/core","version":"1.4.2","description":"Core LangChain.js abstractions",
       "keywords":["llm","ai","LangChain"],"license":"MIT",
       "repository":{"type":"git","url":"git+ssh://git@github.com/langchain-ai/langchainjs.git"},
       "maintainers":[{"name":"someone","email":"someone@example.org"}]}\
      """;

  static final String DEPRECATED =
      """
      {"name":"old-llm-client","description":"An LLM client","keywords":["llm","ai"],
       "license":"MIT","deprecated":"Use new-llm-client"}\
      """;

  final FixtureServer server = new FixtureServer();

  @AfterEach
  void stop() {
    server.close();
  }

  NpmPackageReader.Answer read(String name) {
    return new NpmPackageReader(
            TestHttp.restClientBuilder(
                    Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP)
                .baseUrl(server.baseUrl())
                .build(),
            Resilience.NONE,
            "collector-github-awesome",
            SourceFixtures.CLOCK)
        .read(name, "github-awesome", SourceFixtures.CLOCK.instant());
  }

  @Test
  void aScopedPackageIsReadByItsNameWithTheFactsOfItsLatestVersion() {
    server.route("/@langchain/core/latest", FixtureServer.Reply.ok(SCOPED));

    SourceHit hit = read("@langchain/core").aiPackage();

    assertThat(server.requests).containsExactly("/@langchain/core/latest");
    assertThat(hit.getSourceId()).isEqualTo("github-awesome");
    assertThat(hit.getExternalId()).isEqualTo("https://npmjs.com/package/@langchain/core");
    assertThat(hit.getCanonicalUrl().getValue())
        .isEqualTo("https://npmjs.com/package/@langchain/core");
    assertThat(hit.getTitle()).isEqualTo("@langchain/core");
    assertThat(hit.getType()).isEqualTo(ResourceType.LIBRARY);
    assertThat(hit.getLicense()).isEqualTo("MIT");
    assertThat(hit.getTags()).contains("llm", "langchain");
    assertThat(hit.getRepositoryUrl().getValue())
        .isEqualTo("https://github.com/langchain-ai/langchainjs");
    assertThat(hit.getVitality().getDeprecated()).isFalse();
    assertThat(hit.toString()).doesNotContain("someone@example.org");
  }

  @Test
  void anUnscopedPackageIsReadByItsName() {
    server.route(
        "/ai/latest",
        FixtureServer.Reply.ok(
            "{\"name\":\"ai\",\"description\":\"AI SDK\",\"keywords\":[\"llm\",\"ai\"]}"));

    assertThat(read("ai").aiPackage().getTitle()).isEqualTo("ai");
    assertThat(server.requests).containsExactly("/ai/latest");
  }

  @Test
  void aDeprecatedPackageStatesItSoTheVitalityRuleRefusesIt() {
    server.route("/old-llm-client/latest", FixtureServer.Reply.ok(DEPRECATED));

    assertThat(read("old-llm-client").aiPackage().getVitality().getDeprecated()).isTrue();
  }

  @Test
  void aPackageThatIsNotAboutAiIsFoundButNotKept() {
    server.route(
        "/left-pad/latest",
        FixtureServer.Reply.ok("{\"name\":\"left-pad\",\"description\":\"Pads a string\"}"));

    NpmPackageReader.Answer answer = read("left-pad");

    assertThat(answer.found()).isTrue();
    assertThat(answer.aiPackage()).isNull();
  }

  @Test
  void aPackageTheRegistryDoesNotKnowIsNotFoundAndIsNoFailure() {
    server.route("/gone-llm/latest", new FixtureServer.Reply(404, "\"Not Found\"", Map.of(), 0));

    assertThat(read("gone-llm")).isEqualTo(new NpmPackageReader.Answer(false, null));
  }

  @Test
  void anOutageIsAFailureOfTheSource() {
    server.route("/ai/latest", new FixtureServer.Reply(500, "", Map.of(), 0));

    assertThatThrownBy(() -> read("ai")).isInstanceOf(SourceFailure.Unavailable.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "..", "../admin", "@scope", "@scope/a/b", "Has Space", "a?b=1", "-/v1/search"})
  void aNameThatIsNotAPackageNameIsNeverSent(String name) {
    assertThat(read(name).found()).isFalse();
    assertThat(server.requests).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({
    "https://www.npmjs.com/package/ai, ai",
    "https://npmjs.com/package/@langchain/core, @langchain/core",
    "https://www.npmjs.com/package/@langchain/core/v/1.4.2, @langchain/core",
    "https://www.npmjs.com/package/ai/v/7.0.1, ai",
  })
  void aPackagePageNamesItsPackage(String page, String name) {
    assertThat(NpmPackageReader.nameOf(CanonicalUrl.of(page))).isEqualTo(name);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://www.npmjs.com/search?q=llm",
        "https://www.npmjs.com/~someone",
        "https://www.npmjs.com/package",
        "https://www.npmjs.com/package/ai/access",
        "https://www.npmjs.com/org/langchain",
        "https://example.com/package/ai"
      })
  void anyOtherPageNamesNoPackage(String page) {
    assertThat(NpmPackageReader.nameOf(CanonicalUrl.of(page))).isNull();
  }
}
