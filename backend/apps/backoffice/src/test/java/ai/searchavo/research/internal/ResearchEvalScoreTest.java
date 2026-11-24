package ai.searchavo.research.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.searchavo.research.ResearchAnswer;
import ai.searchavo.research.ResearchResult;
import ai.searchavo.research.ResearchRun;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResearchEvalScoreTest {

  private static final String HEADER =
      "id\titem_url\tkind\ttitle_found\ttype_found\tdecision\texpected_type\texpected_canonical_url"
          + "\texpected_title\texpected_license\texpected_pricing\texpected_open_source";

  private final ResearchLabels labels =
      ResearchLabels.parse(
          List.of(
              HEADER,
              "r001\thttps://acme.example/notes\tRESOURCE_CANDIDATE\tAcme | Notes\tPRODUCT\tKEEP\tPRODUCT"
                  + "\thttps://acme.example\tAcme Notes\t\tFREEMIUM\t",
              "r002\thttps://list.example/best\tRESOURCE_CANDIDATE\tBest tools\tARTICLE\tREJECT\t\t\t\t\t\t",
              "r003\thttps://lib.example\tRESOURCE_CANDIDATE\tlib\tLIBRARY\tKEEP\tLIBRARY\t\t\tMIT\t\ttrue",
              "p001\thttps://open.example\tRESOURCE_CANDIDATE\topen\tPRODUCT\t\t\t\t\t\t\t"));

  @Test
  void onlyDecidedRowsAreLabelsAndEmptyCellsExpectNothing() {
    assertThat(labels.labels())
        .extracting(ResearchLabels.Label::id)
        .containsExactly("r001", "r002", "r003");
    assertThat(labels.labels().get(1).expected()).isEmpty();
    assertThat(labels.labels().get(2).expected()).containsOnlyKeys("type", "license", "openSource");
  }

  @Test
  void anItemLabelledDuplicateIsLeftOutOfTheVerdictAgreement() {
    ResearchLabels withADuplicate =
        ResearchLabels.parse(
            List.of(
                HEADER,
                "p001\thttps://dup.example\tRESOURCE_CANDIDATE\tdup\tARTICLE\tDUPLICATE\t\t\t\t\t\t",
                "p002\thttps://list.example/best\tRESOURCE_CANDIDATE\tBest\tARTICLE\tREJECT\t\t\t\t\t\t"));

    ResearchEvalScore.Report r =
        ResearchEvalScore.score(
            "fast",
            List.of(
                done(
                    withADuplicate.labels().get(0),
                    ResearchAnswer.Verdict.REJECT,
                    List.of(),
                    10_000,
                    3),
                done(
                    withADuplicate.labels().get(1),
                    ResearchAnswer.Verdict.REJECT,
                    List.of(),
                    10_000,
                    3)));

    assertThat(r.items()).isEqualTo(2);
    assertThat(r.verdict()).isEqualTo(new ResearchEvalScore.Agreement(1, 1));
  }

  @Test
  void aFileWithAnotherHeaderIsRefused() {
    assertThatThrownBy(() -> ResearchLabels.parse(List.of("id\turl")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void verdictsValuesAndConfirmedOnesAreCountedAgainstTheAdmin() {
    ResearchEvalScore.Report r =
        ResearchEvalScore.score(
            "fast",
            List.of(
                done(
                    labels.labels().get(0),
                    ResearchAnswer.Verdict.KEEP,
                    List.of(
                        attribute(
                            "canonicalUrl",
                            "https://www.acme.example/",
                            ResearchResult.Status.CONFIRMED),
                        attribute("title", "acme notes", ResearchResult.Status.CONFIRMED),
                        attribute("pricing", "PAID", ResearchResult.Status.CONFIRMED)),
                    20_000,
                    6),
                done(labels.labels().get(1), ResearchAnswer.Verdict.KEEP, List.of(), 10_000, 3),
                new ResearchEvalScore.Outcome(
                    labels.labels().get(2),
                    ResearchRun.Status.FAILED,
                    null,
                    "stopped after 12 tool calls",
                    180_000,
                    13,
                    9_000,
                    500)));

    assertThat(r.items()).isEqualTo(3);
    assertThat(r.rejects()).isEqualTo(1);
    assertThat(r.failed()).isEqualTo(1);
    assertThat(r.verdict()).isEqualTo(new ResearchEvalScore.Agreement(1, 2));
    assertThat(r.attributes().get("canonicalUrl")).isEqualTo(new ResearchEvalScore.Agreement(1, 1));
    assertThat(r.attributes().get("title")).isEqualTo(new ResearchEvalScore.Agreement(1, 1));
    assertThat(r.attributes().get("pricing")).isEqualTo(new ResearchEvalScore.Agreement(0, 1));
    assertThat(r.attributes().get("license")).isEqualTo(new ResearchEvalScore.Agreement(0, 0));
    assertThat(r.confirmedRight()).isEqualTo(new ResearchEvalScore.Agreement(2, 3));
    assertThat(r.p50Millis()).isEqualTo(20_000);
    assertThat(r.p95Millis()).isEqualTo(180_000);
    assertThat(r.modelCallsPerItem())
        .isCloseTo(22 / 3.0, org.assertj.core.data.Offset.offset(0.001));
    assertThat(r.rows())
        .extracting(ResearchEvalScore.Row::verdict)
        .containsExactly("KEEP", "KEEP", "FAILED");
    assertThat(r.rows().getFirst().detail()).isEqualTo("differs: pricing");
  }

  @Test
  void theReportSaysWhenTheLabelsAreTooFewToDecide() {
    ResearchEvalScore.Report r = ResearchEvalScore.score("fast", List.of());

    assertThat(ResearchEvalScore.markdown(r))
        .contains("Too few labels for a decision: 0 items, 0 rejects");
    assertThat(ResearchEvalScore.json(r))
        .contains("\"items\": 0")
        .contains("\"verdictAgreement\": 0.0000");
  }

  @Test
  void urlsCompareWithoutSchemeWwwOrTrailingSlash() {
    assertThat(
            ResearchEvalScore.same(
                "canonicalUrl", "http://www.Acme.example/", "https://acme.example"))
        .isTrue();
    assertThat(ResearchEvalScore.same("openSource", "true", "t")).isTrue();
    assertThat(ResearchEvalScore.same("type", "LIBRARY", "PRODUCT")).isFalse();
  }

  private static ResearchEvalScore.Outcome done(
      ResearchLabels.Label label,
      ResearchAnswer.Verdict verdict,
      List<ResearchResult.Attribute> attributes,
      long millis,
      int calls) {
    return new ResearchEvalScore.Outcome(
        label,
        ResearchRun.Status.DONE,
        new ResearchResult(verdict, "reason", null, attributes, List.of()),
        null,
        millis,
        calls,
        1_000,
        100);
  }

  private static ResearchResult.Attribute attribute(
      String name, String value, ResearchResult.Status status) {
    return new ResearchResult.Attribute(name, null, List.of(value), status, List.of());
  }
}
