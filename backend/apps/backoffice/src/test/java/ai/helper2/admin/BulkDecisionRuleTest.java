package ai.helper2.admin;

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
    assertThat(BulkDecisionRule.allows(REJECTED, kind, List.of("pricing"))).isTrue();
  }

  @ParameterizedTest
  @EnumSource(value = Kind.class, names = "ENRICHMENT_VALIDATION", mode = EnumSource.Mode.EXCLUDE)
  void acceptingIsRefusedForEveryKindThatAddsPublishesOrClosesAReport(Kind kind) {
    assertThat(BulkDecisionRule.allows(ACCEPTED, kind, List.of())).isFalse();
  }

  @Test
  void acceptingAnEnrichmentCheckWithoutConflictIsAllowed() {
    assertThat(BulkDecisionRule.allows(ACCEPTED, Kind.ENRICHMENT_VALIDATION, List.of())).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"license", "openSource", "pricing"})
  void acceptingIsRefusedWhenTheRunConflictsOnADecisiveField(String field) {
    assertThat(
            BulkDecisionRule.allows(
                ACCEPTED, Kind.ENRICHMENT_VALIDATION, List.of("platforms", field)))
        .isFalse();
  }

  @Test
  void aConflictOnAnotherFieldDoesNotBlockAccepting() {
    assertThat(
            BulkDecisionRule.allows(
                ACCEPTED, Kind.ENRICHMENT_VALIDATION, List.of("platforms", "useCase")))
        .isTrue();
  }

  @Test
  void openIsNoDecision() {
    assertThat(BulkDecisionRule.allows(OPEN, Kind.ENRICHMENT_VALIDATION, List.of())).isFalse();
  }
}
