package ai.helper2.scout.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.scout.Candidate;
import ai.helper2.scout.usecase.AcceptedCandidate;
import ai.helper2.scout.usecase.InvalidCandidate;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceType;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ResourceCandidateJsonTest {

  private final ResourceCandidateJson json =
      new ResourceCandidateJson(JsonMapper.builder().build());

  private static Candidate candidate(String title) {
    return new Candidate(
        CanonicalUrl.of("https://openwebui.com/"),
        title,
        "Self-hosted AI platform.",
        "local-ai, self-hosted",
        ResourceType.PRODUCT,
        Set.of("self-hosted", "local-ai"));
  }

  @Test
  void whatTheCollectorWritesIsWhatAcceptingReads() {
    AcceptedCandidate read = json.read(json.write("tavily-discovery", candidate("Open WebUI")));
    assertThat(read)
        .isEqualTo(
            new AcceptedCandidate(
                "tavily-discovery",
                "https://openwebui.com",
                "Open WebUI",
                "Self-hosted AI platform.",
                ResourceType.PRODUCT,
                Set.of("local-ai", "self-hosted")));
  }

  @Test
  void aLongTitleIsCutSoTheItemCanBeAccepted() {
    AcceptedCandidate read = json.read(json.write("tavily-discovery", candidate("x".repeat(500))));
    assertThat(read.getTitle()).hasSize(ResourceCandidateJson.MAX_TITLE);
  }

  @Test
  void detailsThatAreNotACandidateAreRefused() {
    assertThatThrownBy(() -> json.read("not json")).isInstanceOf(InvalidCandidate.class);
    assertThatThrownBy(() -> json.read(null)).isInstanceOf(InvalidCandidate.class);
    assertThatThrownBy(() -> json.read("{\"url\":\"https://x.ai\"}"))
        .isInstanceOf(InvalidCandidate.class);
    assertThatThrownBy(() ->
            json.read(
                "{\"sourceId\":\"s\",\"url\":\"https://x.ai\",\"title\":\"X\",\"type\":\"WIDGET\"}"))
        .isInstanceOf(InvalidCandidate.class);
    assertThatThrownBy(() ->
            json.read(
                "{\"sourceId\":\"s\",\"url\":\"https://x.ai\",\"title\":\"X\",\"type\":\"PRODUCT\",\"tags\":\"a\"}"))
        .isInstanceOf(InvalidCandidate.class);
  }
}
