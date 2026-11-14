package ai.helper2.scout.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.scout.Candidate;
import ai.helper2.scout.CandidateSignals;
import ai.helper2.scout.usecase.AcceptedCandidate;
import ai.helper2.scout.usecase.CandidateEdit;
import ai.helper2.scout.usecase.InvalidCandidate;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceType;
import java.time.Instant;
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
                            Map.of("category", "self-hosted", "rank", 1)))),
                List.of("discovery-self-hosted")));
  }

  @Test
  void thePackagesASourceFoundReachTheAcceptedCandidate() {
    Map<String, Object> registryPackage =
        Map.of(
            "license", "MIT", "packages", List.of(Map.of("ecosystem", "PYPI", "name", "ollama")));
    Candidate sdk =
        new Candidate(
            CanonicalUrl.of("https://github.com/ollama/ollama-python"),
            "ollama/ollama-python",
            "Ollama Python library",
            null,
            ResourceType.LIBRARY,
            Set.of("python"),
            Map.of("package", registryPackage));

    AcceptedCandidate read = json.read(json.write("github-sdks", sdk));

    assertThat(read.getFacts()).containsEntry("package", registryPackage);
  }

  private static final Instant PUSHED = Instant.parse("2026-11-01T08:30:00Z");

  private static Candidate leader(CandidateSignals signals) {
    return new Candidate(
        CanonicalUrl.of("https://github.com/ollama/ollama-python"),
        "ollama/ollama-python",
        "Ollama Python library",
        null,
        ResourceType.LIBRARY,
        Set.of("llm"),
        Map.of(),
        signals);
  }

  @Test
  void whatASourceStatesAboutARepositoryReachesTheAcceptedCandidate() {
    CandidateSignals stated = new CandidateSignals(10689L, PUSHED, false, true);

    AcceptedCandidate read = json.read(json.write("github-topic-leaders", leader(stated)));

    assertThat(read.getSignals()).isEqualTo(stated);
  }

  @Test
  void aSignalTheSourceDoesNotStateStaysUnknown() {
    CandidateSignals onlyStars = new CandidateSignals(10689L);

    String written = json.write("github-topic-leaders", leader(onlyStars));

    assertThat(written).contains("\"signals\":{\"stars\":10689}");
    assertThat(json.read(written).getSignals()).isEqualTo(onlyStars);
    assertThat(json.write("github-topic-leaders", leader())).doesNotContain("signals");
  }

  @Test
  void anOpenCandidateWrittenBeforeSignalsReadsThemAsUnknown() {
    AcceptedCandidate read =
        json.read(
            "{\"version\":2,\"sourceId\":\"github-sdks\","
                + "\"url\":\"https://github.com/ollama/ollama-python\","
                + "\"title\":\"ollama/ollama-python\",\"type\":\"LIBRARY\",\"tags\":[\"python\"],"
                + "\"foundBy\":[{\"sourceId\":\"github-sdks\",\"package\":{\"license\":\"MIT\"}}]}");

    assertThat(read.getSignals()).isEqualTo(CandidateSignals.UNKNOWN);
    assertThat(read.getFacts()).containsEntry("package", Map.of("license", "MIT"));
  }

  @Test
  void aSignalThatCannotBeReadIsUnknown() {
    AcceptedCandidate read =
        json.read(
            "{\"version\":2,\"sourceId\":\"github-sdks\",\"url\":\"https://github.com/a/b\","
                + "\"title\":\"a/b\",\"type\":\"LIBRARY\",\"tags\":[],"
                + "\"foundBy\":[{\"sourceId\":\"github-sdks\",\"signals\":"
                + "{\"stars\":\"many\",\"lastPush\":\"soon\",\"archived\":\"no\",\"fork\":1}}]}");

    assertThat(read.getSignals()).isEqualTo(CandidateSignals.UNKNOWN);
  }

  @Test
  void theSameSourceWithNewSignalsRefreshesItsEntry() {
    String first = json.write("github-topic-leaders", leader(new CandidateSignals(10689L)));

    String again = json.merge(first, "github-topic-leaders", leader(new CandidateSignals(10700L)));

    assertThat(json.read(again).getSignals()).isEqualTo(new CandidateSignals(10700L));
    assertThat(json.merge(again, "github-topic-leaders", leader(new CandidateSignals(10700L))))
        .isNull();
  }

  @Test
  void theSourceThatOpenedTheItemKeepsItsSignalsWhenAnotherFinderJoins() {
    String merged =
        json.merge(
            json.write("github-sdks", leader(new CandidateSignals(10689L, PUSHED, false, false))),
            "github-topic-leaders",
            leader(new CandidateSignals(10700L, PUSHED, true, false)));

    assertThat(json.read(merged).getSignals())
        .isEqualTo(new CandidateSignals(10689L, PUSHED, false, false));
  }

  private static Candidate sdk(Map<String, Object> registryPackage) {
    return new Candidate(
        CanonicalUrl.of("https://github.com/ollama/ollama-python"),
        "ollama/ollama-python",
        "Ollama Python library",
        null,
        ResourceType.LIBRARY,
        Set.of("python"),
        Map.of("package", registryPackage));
  }

  private static Candidate leader() {
    return leader(CandidateSignals.UNKNOWN);
  }

  @Test
  void anItemOpenedByAnotherSourceNeverTakesThePackagesOfALaterFinder() {
    String merged =
        json.merge(
            json.write("github-topic-leaders", leader()),
            "github-sdks",
            sdk(Map.of("license", "MIT")));

    AcceptedCandidate read = json.read(merged);

    assertThat(read.getSourceId()).isEqualTo("github-topic-leaders");
    assertThat(read.getFacts()).doesNotContainKey("package");
  }

  @Test
  void theSourceThatOpenedTheItemKeepsItsPackagesWhenAnotherFinderJoins() {
    String merged =
        json.merge(
            json.write("github-sdks", sdk(Map.of("license", "MIT"))),
            "github-topic-leaders",
            leader());

    assertThat(json.read(merged).getFacts()).containsEntry("package", Map.of("license", "MIT"));
  }

  @Test
  void theSameSourceWithNewFactsRefreshesItsEntry() {
    String first = json.write("github-sdks", sdk(Map.of("license", "MIT")));

    String again = json.merge(first, "github-sdks", sdk(Map.of("license", "Apache-2.0")));

    assertThat(json.read(again).getFacts())
        .containsEntry("package", Map.of("license", "Apache-2.0"));
    assertThat(json.merge(again, "github-sdks", sdk(Map.of("license", "Apache-2.0")))).isNull();
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
    assertThat(read.getFinders())
        .containsExactly("discovery-self-hosted", "discovery-agent-platforms");
    assertThat(json.finders(merged))
        .containsExactly("discovery-self-hosted", "discovery-agent-platforms");
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

  @Test
  void anAdminsEditIsMarkedAndAFinderThatComesLaterKeepsIt() {
    String found = json.write("discovery-self-hosted", candidate("Pricing | Open WebUI"));
    String edited =
        json.edit(found, new CandidateEdit("Open WebUI", ResourceType.MODEL, Set.of("local-ai")));

    String merged = json.merge(edited, "discovery-agent-platforms", agent("agent-platforms"));

    AcceptedCandidate read = json.read(merged);
    assertThat(read.getTitle()).isEqualTo("Open WebUI");
    assertThat(read.getType()).isEqualTo(ResourceType.MODEL);
    assertThat(read.getTags()).containsExactly("local-ai");
    assertThat(read.getTypeChosenByAdmin()).isTrue();
    assertThat(read.getFinders())
        .containsExactly("discovery-self-hosted", "discovery-agent-platforms");
    assertThat(read.getFacts()).containsEntry("edited", List.of("tags", "title", "type"));
    assertThat(json.reason(merged))
        .isEqualTo(
            "New MODEL (local-ai) found by discovery-self-hosted, discovery-agent-platforms:"
                + " Open WebUI");
  }

  @Test
  void anEditOfOnlyTheTitleLeavesTheTypeToTheFinders() {
    String edited =
        json.edit(
            json.write("discovery-self-hosted", candidate("x")),
            new CandidateEdit("Open WebUI", null, null));

    AcceptedCandidate read =
        json.read(json.merge(edited, "discovery-agent-platforms", agent("agent-platforms")));
    assertThat(read.getType()).isEqualTo(ResourceType.AGENT);
    assertThat(read.getTags()).contains("agent-platforms");
    assertThat(read.getTypeChosenByAdmin()).isFalse();
    assertThat(read.getFacts()).containsEntry("edited", List.of("title"));
  }

  @Test
  void anEditOfDetailsThatAreNotACandidateIsRefused() {
    assertThatThrownBy(() -> json.edit("not json", new CandidateEdit("x", null, null)))
        .isInstanceOf(InvalidCandidate.class);
    assertThatThrownBy(() -> json.edit("[1]", new CandidateEdit("x", null, null)))
        .isInstanceOf(InvalidCandidate.class);
  }
}
