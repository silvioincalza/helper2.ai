package ai.searchavo.sources.huggingface.scout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.search.PopularityKind;
import ai.searchavo.search.SourceFailure;
import ai.searchavo.search.SourceHit;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.sources.SourceFixtures;
import ai.searchavo.testing.FixtureServer;
import ai.searchavo.testing.TestHttp;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HuggingFaceModelReaderTest {

  static final String MODEL =
      """
      {"id":"acme/voice-model","likes":120,"downloads":5000,"disabled":false,
       "tags":["transformers","license:mit","region:us"],"pipeline_tag":"text-to-speech",
       "createdAt":"2024-01-01T00:00:00.000Z","lastModified":"2026-10-01T00:00:00.000Z"}\
      """;

  final FixtureServer server = new FixtureServer();

  @AfterEach
  void stop() {
    server.close();
  }

  HuggingFaceModelReader reader() {
    return new HuggingFaceModelReader(
        TestHttp.restClientBuilder(
                Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP)
            .baseUrl(server.baseUrl())
            .build(),
        Resilience.NONE,
        "collector-github-awesome",
        SourceFixtures.CLOCK);
  }

  Optional<SourceHit> read(String id) {
    return reader().read(id, "github-awesome", SourceFixtures.CLOCK.instant());
  }

  @Test
  void aModelIsReadByItsIdWithTheFactsTheHubStates() {
    server.route("/api/models/acme/voice-model", FixtureServer.Reply.ok(MODEL));

    SourceHit hit = read("acme/voice-model").orElseThrow();

    assertThat(server.requests.getFirst())
        .startsWith("/api/models/acme/voice-model?")
        .contains("likes")
        .contains("disabled");
    assertThat(hit.getSourceId()).isEqualTo("github-awesome");
    assertThat(hit.getType()).isEqualTo(ResourceType.MODEL);
    assertThat(hit.getCanonicalUrl().getValue())
        .isEqualTo("https://huggingface.co/acme/voice-model");
    assertThat(hit.getLicense()).isEqualTo("MIT");
    assertThat(hit.getTags()).contains("transformers", "text-to-speech");
    assertThat(hit.getPopularity())
        .filteredOn(p -> p.getKind() == PopularityKind.HF_LIKES)
        .extracting(p -> p.getValue())
        .containsExactly(120L);
    assertThat(hit.getVitality().getDeprecated()).isFalse();
  }

  @Test
  void theRecordIsNamedByItsAddressSoAModelNeverTakesTheRecordOfARepositoryOfTheSameName() {
    server.route("/api/models/acme/voice-model", FixtureServer.Reply.ok(MODEL));

    assertThat(read("acme/voice-model").orElseThrow().getExternalId())
        .isEqualTo("https://huggingface.co/acme/voice-model");
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 404})
  void aModelTheHubDoesNotShowIsNotFoundAndIsNoFailure(int status) {
    server.route(
        "/api/models/acme/gone", new FixtureServer.Reply(status, "{\"error\":\"x\"}", Map.of(), 0));

    assertThat(read("acme/gone")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"acme", "acme/a/b", "../admin", "acme/..", "acme/voice model", "acme/v?x=1", ""})
  void anIdThatIsNotOwnerAndNameIsNeverSent(String id) {
    assertThat(read(id)).isEmpty();
    assertThat(server.requests).isEmpty();
  }

  @Test
  void anOutageIsAFailureOfTheSource() {
    server.route("/api/models/acme/voice-model", new FixtureServer.Reply(500, "", Map.of(), 0));

    assertThatThrownBy(() -> read("acme/voice-model"))
        .isInstanceOf(SourceFailure.Unavailable.class);
  }
}
