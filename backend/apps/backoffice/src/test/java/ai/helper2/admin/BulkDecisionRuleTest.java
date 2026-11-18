package ai.helper2.admin;

import static ai.helper2.admin.BulkDecisionRule.Research.KEEP_WITHOUT_CONFLICT;
import static ai.helper2.admin.BulkDecisionRule.Research.NOT_KEEP;
import static ai.helper2.catalog.ReviewQueue.Status.ACCEPTED;
import static ai.helper2.catalog.ReviewQueue.Status.OPEN;
import static ai.helper2.catalog.ReviewQueue.Status.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.catalog.ReviewQueue.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class BulkDecisionRuleTest {

  @ParameterizedTest
  @EnumSource(Kind.class)
  void rejectingIsAllowedForEveryKind(Kind kind) {
    assertThat(BulkDecisionRule.allows(REJECTED, kind, List.of("pricing"), NOT_KEEP)).isTrue();
  }

  @ParameterizedTest
  @EnumSource(value = Kind.class, names = "ENRICHMENT_VALIDATION", mode = EnumSource.Mode.EXCLUDE)
  void acceptingIsRefusedForEveryKindWithoutAResearchedKeep(Kind kind) {
    assertThat(BulkDecisionRule.allows(ACCEPTED, kind, List.of(), NOT_KEEP)).isFalse();
  }

  @ParameterizedTest
  @EnumSource(
      value = Kind.class,
      names = {"RESOURCE_CANDIDATE", "COURSE_CANDIDATE"})
  void aCandidateResearchedAsKeepWithoutConflictIsAccepted(Kind kind) {
    assertThat(BulkDecisionRule.allows(ACCEPTED, kind, List.of(), KEEP_WITHOUT_CONFLICT)).isTrue();
  }

  @ParameterizedTest
  @EnumSource(
      value = Kind.class,
      names = {
        "RELATIONSHIP_CANDIDATE",
        "USER_REPORT",
        "EXAMPLE_CANDIDATE",
        "KNOWN_TARGET_CANDIDATE"
      })
  void aResearchedKeepAcceptsNoOtherKind(Kind kind) {
    assertThat(BulkDecisionRule.allows(ACCEPTED, kind, List.of(), KEEP_WITHOUT_CONFLICT)).isFalse();
  }

  @Test
  void acceptingAnEnrichmentCheckWithoutConflictIsAllowed() {
    assertThat(BulkDecisionRule.allows(ACCEPTED, Kind.ENRICHMENT_VALIDATION, List.of(), NOT_KEEP))
        .isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"license", "openSource", "pricing"})
  void acceptingIsRefusedWhenTheRunConflictsOnADecisiveField(String field) {
    assertThat(
            BulkDecisionRule.allows(
                ACCEPTED, Kind.ENRICHMENT_VALIDATION, List.of("platforms", field), NOT_KEEP))
        .isFalse();
  }

  @Test
  void aConflictOnAnotherFieldDoesNotBlockAccepting() {
    assertThat(
            BulkDecisionRule.allows(
                ACCEPTED, Kind.ENRICHMENT_VALIDATION, List.of("platforms", "useCase"), NOT_KEEP))
        .isTrue();
  }

  @Test
  void openIsNoDecision() {
    assertThat(
            BulkDecisionRule.allows(
                OPEN, Kind.ENRICHMENT_VALIDATION, List.of(), KEEP_WITHOUT_CONFLICT))
        .isFalse();
  }
}
