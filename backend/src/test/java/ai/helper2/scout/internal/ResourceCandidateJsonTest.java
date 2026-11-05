package ai.helper2.scout.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.scout.Candidate;
import ai.helper2.scout.usecase.AcceptedCandidate;
import ai.helper2.scout.usecase.InvalidCandidate;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceType;
import java.util.List;
import java.util.Map;
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
        Set.of("self-hosted", "local-ai"),
        Map.of("discovery", Map.of("category", "self-hosted", "rank", 1)));
  }

  private static Candidate agent(String category) {
    return new Candidate(
        CanonicalUrl.of("https://openwebui.com/"),
        "Open WebUI",
        "An agent platform.",
        category,
        ResourceType.AGENT,
        Set.of(category),
        Map.of("discovery", Map.of("category", category, "rank", 0)));
  }

  @Test
  void whatTheCollectorWritesIsWhatAcceptingReads() {
    AcceptedCandidate read =
        json.read(json.write("discovery-self-hosted", candidate("Open WebUI")));
    assertThat(read)
        .isEqualTo(
            new AcceptedCandidate(
                "discovery-self-hosted",
                "https://openwebui.com",
                "Open WebUI",
                "Self-hosted AI platform.",
                ResourceType.PRODUCT,
                Set.of("local-ai", "self-hosted"),
                Map.of(
                    "discovery",
                    Map.of("category", "self-hosted", "rank", 1),
                    "categories",
                    List.of("local-ai", "self-hosted"),
                    "foundBy",
                    List.of(
                        Map.of(
                            "sourceId",
                            "discovery-self-hosted",
                            "discovery",
                            Map.of("category", "self-hosted", "rank", 1))))));
  }

  @Test
  void aVersionOneCandidateIsStillReadWithoutFacts() {
    AcceptedCandidate read =
        json.read(
            "{\"sourceId\":\"discovery-coding\",\"url\":\"https://x.ai\",\"title\":\"X\","
                + "\"type\":\"PRODUCT\",\"tags\":[\"coding\"]}");
    assertThat(read.getTags()).containsExactly("coding");
    assertThat(read.getFacts()).isEmpty();
    assertThat(json.finders("{\"sourceId\":\"discovery-coding\"}"))
        .containsExactly("discovery-coding");
  }

  @Test
  void aSecondCategoryJoinsTheCandidateWithItsTagsTheMostSpecificTypeAndItsSearch() {
    String first = json.write("discovery-self-hosted", candidate("Open WebUI"));
    String merged = json.merge(first, "discovery-agent-platforms", agent("agent-platforms"));

    AcceptedCandidate read = json.read(merged);
    assertThat(read.getSourceId()).isEqualTo("discovery-self-hosted");
    assertThat(read.getType()).isEqualTo(ResourceType.AGENT);
    assertThat(read.getTags()).containsExactly("agent-platforms", "local-ai", "self-hosted");
    assertThat(read.getSnippet()).isEqualTo("Self-hosted AI platform.");
    assertThat(json.finders(merged))
        .containsExactly("discovery-self-hosted", "discovery-agent-platforms");
    // The same collector again changes nothing; a less specific type does not replace a more
    // specific one.
    assertThat(json.merge(merged, "discovery-agent-platforms", agent("agent-platforms"))).isNull();
    String third = json.merge(merged, "discovery-local-ai", candidate("Open WebUI"));
    assertThat(json.read(third).getType()).isEqualTo(ResourceType.AGENT);
  }

  @Test
  void joiningKeepsAtMostTheTagsAcceptingAllowsTheFindersCategoriesFirst() {
    Set<String> twelve =
        java.util.stream.IntStream.rangeClosed(1, Candidate.MAX_TAGS)
            .mapToObj(i -> String.format("words-%02d", i))
            .collect(java.util.stream.Collectors.toSet());
    Candidate wide =
        new Candidate(
            CanonicalUrl.of("https://openwebui.com/"),
            "Open WebUI",
            null,
            null,
            ResourceType.PRODUCT,
            twelve);
    String first = json.write("discovery-words-01", wide);
    String merged = json.merge(first, "discovery-agent-platforms", agent("agent-platforms"));

    assertThat(json.read(merged).getTags())
        .hasSize(Candidate.MAX_TAGS)
        .contains("words-01", "agent-platforms", "words-02")
        .doesNotContain("words-12");
  }

  @Test
  void aVersionOneCandidateCanBeJoinedToo() {
    String v1 =
        "{\"sourceId\":\"discovery-coding\",\"url\":\"https://openwebui.com\",\"title\":\"X\","
            + "\"type\":\"PRODUCT\",\"tags\":[\"coding\"]}";
    String merged = json.merge(v1, "discovery-agent-platforms", agent("agent-platforms"));
    assertThat(json.finders(merged))
        .containsExactly("discovery-coding", "discovery-agent-platforms");
    assertThat(json.merge("not json", "discovery-coding", agent("coding"))).isNull();
  }

  @Test
  void aLongTitleIsCutSoTheItemCanBeAccepted() {
    AcceptedCandidate read =
        json.read(json.write("discovery-self-hosted", candidate("x".repeat(500))));
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
