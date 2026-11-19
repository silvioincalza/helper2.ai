package ai.helper2.admin.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.research.ResearchAnswer;
import ai.helper2.research.ResearchResult;
import ai.helper2.research.ResearchRun;
import ai.helper2.research.ResearchSubject;
import ai.helper2.scout.usecase.CandidateEdit;
import ai.helper2.shared.ResourceType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResearchedAcceptanceTest {

  static ResearchResult.Attribute confirmed(String name, String current, String proposed) {
    return new ResearchResult.Attribute(
        name, current, List.of(proposed), ResearchResult.Status.CONFIRMED, List.of());
  }

  static ResearchResult result(ResearchAnswer.Verdict verdict, ResearchResult.Attribute... a) {
    return new ResearchResult(verdict, "checked", null, List.of(a), List.of());
  }

  static ResearchRun run(ResearchRun.Status status, ResearchResult result) {
    return new ResearchRun(
        UUID.randomUUID(),
        UUID.randomUUID(),
        status,
        "m",
        "admin",
        Instant.EPOCH,
        Instant.EPOCH,
        Instant.EPOCH,
        3,
        1,
        1,
        result,
        null);
  }

  @Test
  void onlyADoneKeepWithoutConflictIsAcceptableInBulk() {
    ResearchResult keep = result(ResearchAnswer.Verdict.KEEP, confirmed("title", "a", "b"));
    assertThat(ResearchedAcceptance.acceptable(run(ResearchRun.Status.DONE, keep))).isTrue();
    assertThat(ResearchedAcceptance.acceptable(null)).isFalse();
    assertThat(ResearchedAcceptance.acceptable(run(ResearchRun.Status.RUNNING, keep))).isFalse();
    assertThat(
            ResearchedAcceptance.acceptable(
                run(ResearchRun.Status.DONE, result(ResearchAnswer.Verdict.REJECT))))
        .isFalse();
    assertThat(
            ResearchedAcceptance.acceptable(
                run(
                    ResearchRun.Status.DONE,
                    result(
                        ResearchAnswer.Verdict.KEEP,
                        new ResearchResult.Attribute(
                            "license",
                            null,
                            List.of("MIT", "Apache-2.0"),
                            ResearchResult.Status.CONFLICT,
                            List.of())))))
        .isFalse();
  }

  @Test
  void theEditHoldsEveryConfirmedChangeAndNothingElse() {
    CandidateEdit edit =
        ResearchedAcceptance.edit(
                result(
                    ResearchAnswer.Verdict.KEEP,
                    confirmed("title", "Acme | AI", "Acme"),
                    confirmed("type", "PRODUCT", "LIBRARY"),
                    confirmed("tags", "x", "Note Taking, rag"),
                    confirmed("openSource", null, "true"),
                    confirmed("publishedAt", null, "2025-03-02"),
                    confirmed("description", null, "Takes notes."),
                    confirmed("language", "en", "en"),
                    new ResearchResult.Attribute(
                        "pricing",
                        null,
                        List.of("PAID"),
                        ResearchResult.Status.SINGLE_SOURCE,
                        List.of())))
            .orElseThrow();
    assertThat(edit.getTitle()).isEqualTo("Acme");
    assertThat(edit.getType()).isEqualTo(ResourceType.LIBRARY);
    assertThat(edit.getTags()).isEqualTo(Set.of("note-taking", "rag"));
    assertThat(edit.getOpenSource()).isTrue();
    assertThat(edit.getPublishedAt()).isEqualTo(LocalDate.of(2025, 3, 2));
    assertThat(edit.getDescription()).isEqualTo("Takes notes.");
    assertThat(edit.getDescriptionByAi()).isTrue();
    assertThat(edit.getLanguage()).isNull();
    assertThat(edit.getPricing()).isNull();
  }

  @Test
  void theResearchDescribesTheCandidateOnlyWhileWhatItSawIsUnchanged() {
    ResearchResult result =
        result(
            ResearchAnswer.Verdict.KEEP,
            confirmed("title", "Acme | AI", "Acme"),
            confirmed("canonicalUrl", "https://acme.example/x", "https://acme.example/"),
            confirmed("license", null, "MIT"));
    UUID id = UUID.randomUUID();
    ResearchSubject same =
        new ResearchSubject(
            id,
            "RESOURCE_CANDIDATE",
            "https://acme.example/x",
            "Acme | AI",
            "PRODUCT",
            List.of(),
            null);
    ResearchSubject editedUrl =
        new ResearchSubject(
            id,
            "RESOURCE_CANDIDATE",
            "https://acme.example/other",
            "Acme | AI",
            "PRODUCT",
            List.of(),
            null);
    ResearchSubject editedTitle =
        new ResearchSubject(
            id,
            "RESOURCE_CANDIDATE",
            "https://acme.example/x",
            "Acme Notes",
            "PRODUCT",
            List.of(),
            null);

    assertThat(ResearchedAcceptance.describes(result, same)).isTrue();
    assertThat(ResearchedAcceptance.describes(result, editedUrl)).isFalse();
    assertThat(ResearchedAcceptance.describes(result, editedTitle)).isFalse();
  }

  @Test
  void anEditToAConfirmedValueAfterTheResearchMakesItStale() {
    ResearchResult result =
        result(
            ResearchAnswer.Verdict.KEEP,
            confirmed("title", "Acme", "Acme"),
            confirmed("license", null, "MIT"),
            confirmed("pricing", "free", "freemium"));
    UUID id = UUID.randomUUID();

    assertThat(ResearchedAcceptance.describes(result, subject(id, Map.of("pricing", "free"))))
        .isTrue();
    assertThat(
            ResearchedAcceptance.describes(
                result, subject(id, Map.of("pricing", "free", "license", "Apache-2.0"))))
        .isFalse();
    assertThat(ResearchedAcceptance.describes(result, subject(id, Map.of("pricing", "paid"))))
        .isFalse();
    assertThat(ResearchedAcceptance.describes(result, subject(id, Map.of()))).isFalse();
  }

  private static ResearchSubject subject(UUID id, Map<String, String> confirmed) {
    return new ResearchSubject(
        id,
        "RESOURCE_CANDIDATE",
        "https://acme.example/",
        "Acme",
        "PRODUCT",
        List.of(),
        null,
        confirmed);
  }

  @Test
  void noConfirmedChangeIsNoEdit() {
    assertThat(
            ResearchedAcceptance.edit(
                result(ResearchAnswer.Verdict.KEEP, confirmed("language", "en", "en"))))
        .isEmpty();
  }

  @Test
  void aValueTheCandidateCannotTakeIsRefused() {
    assertThatThrownBy(() ->
            ResearchedAcceptance.edit(
                result(ResearchAnswer.Verdict.KEEP, confirmed("type", "PRODUCT", "library"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            ResearchedAcceptance.edit(
                result(ResearchAnswer.Verdict.KEEP, confirmed("openSource", null, "yes"))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
