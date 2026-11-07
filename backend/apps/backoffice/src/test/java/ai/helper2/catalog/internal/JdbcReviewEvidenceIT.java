package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.ReviewEvidence;
import ai.helper2.catalog.ReviewEvidence.Evidence;
import ai.helper2.catalog.ReviewEvidence.Field;
import ai.helper2.catalog.ReviewEvidence.Quote;
import ai.helper2.catalog.ReviewEvidenceFixture;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.shared.Verification;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** A review item's evidence, read through its link on real PostgreSQL (ADR 0026). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcReviewEvidenceIT {

  @Autowired ReviewEvidence evidence;

  @Autowired JdbcClient jdbc;

  ReviewEvidenceFixture fixture;

  @BeforeEach
  void oneRunWithItsItems() {
    fixture = new ReviewEvidenceFixture(jdbc).insert();
  }

  @AfterEach
  void clean() {
    fixture.delete();
  }

  @Test
  void aValidationItemShowsItsRunErrorsAndFieldsConflictingFirst() {
    Evidence e = evidence.of(fixture.validationItem);

    assertThat(e.run()).isNotNull();
    assertThat(e.run().id()).isEqualTo(fixture.run);
    assertThat(e.run().model()).isEqualTo("nvidia/nemotron-test");
    assertThat(e.run().promptVersion()).isEqualTo("enrich-v9");
    assertThat(e.run().outcome()).isEqualTo("PARTIAL");
    assertThat(e.run().extraEvidence()).isEqualTo("github-readme:acme/tool");
    assertThat(e.validationErrors())
        .containsExactly("pricing: the quotes disagree", "platforms: not a known platform");
    assertThat(e.fields())
        .extracting(Field::field, Field::verification)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("pricing", Verification.CONFLICTING),
            org.assertj.core.groups.Tuple.tuple("audience", Verification.UNVERIFIED),
            org.assertj.core.groups.Tuple.tuple("license", Verification.VERIFIED));
    assertThat(e.relationship()).isNull();
    assertThat(e.aiWritten()).isTrue();
  }

  @Test
  void eachFieldKeepsItsNewestFiveQuotesWithTheirSource() {
    Evidence e = evidence.of(fixture.validationItem);

    Field pricing = e.fields().getFirst();
    assertThat(pricing.quotes()).hasSize(ReviewEvidence.MAX_QUOTES);
    assertThat(pricing.quotes())
        .extracting(Quote::excerpt)
        .containsExactly(
            "Pricing quote 6",
            "Pricing quote 5",
            "Pricing quote 4",
            "Pricing quote 3",
            "Pricing quote 2");
    assertThat(pricing.quotes().getFirst().sourceId()).isEqualTo(ReviewEvidenceFixture.SOURCE);
    Field license = e.fields().getLast();
    assertThat(license.quotes()).singleElement().extracting(Quote::sourceId).isNull();
    assertThat(license.quotes().getFirst().sourceUrl()).isEqualTo("https://acme.example/page/0");
    Field audience = e.fields().get(1);
    assertThat(audience.origin()).isEqualTo(ValueOrigin.AI_ESTIMATED);
    assertThat(audience.quotes()).isEmpty();
  }

  @Test
  void aRelationshipCandidateShowsTheRelationshipAndItsQuotesNotTheFields() {
    Evidence e = evidence.of(fixture.relationshipItem);

    assertThat(e.run()).isNotNull();
    assertThat(e.fields()).isEmpty();
    assertThat(e.validationErrors()).isEmpty();
    assertThat(e.relationship()).isNotNull();
    assertThat(e.relationship().kind()).isEqualTo("INTEGRATES_WITH");
    assertThat(e.relationship().target()).isEqualTo("LangChain");
    assertThat(e.relationship().targetId()).isNull();
    assertThat(e.relationship().origin()).isEqualTo(ValueOrigin.SELF_DECLARED);
    assertThat(e.relationship().quotes())
        .singleElement()
        .extracting(Quote::excerpt)
        .isEqualTo("Works with LangChain.");
  }

  @Test
  void theConflictingFieldsOfAnItemsRunAreReadOnTheirOwn() {
    assertThat(evidence.conflictingFields(fixture.validationItem)).containsExactly("pricing");
    assertThat(evidence.conflictingFields(fixture.enrichmentItemConflictingOn("license")))
        .containsExactly("license");
    assertThat(evidence.conflictingFields(fixture.reportItem)).isEmpty();
    assertThat(evidence.conflictingFields(UUID.randomUUID())).isEmpty();
  }

  @Test
  void anItemWithNoLinkOrNoItemAtAllHasNoEvidence() {
    assertThat(evidence.of(fixture.reportItem)).isEqualTo(Evidence.NONE);
    assertThat(evidence.of(UUID.randomUUID())).isEqualTo(Evidence.NONE);
    assertThat(Evidence.NONE.aiWritten()).isFalse();
  }

  @Test
  void aValidationErrorAloneIsAiWrittenBecauseItQuotesTheRefusedOutput() {
    assertThat(new Evidence(null, List.of("pricing: no quote"), List.of(), null).aiWritten())
        .isTrue();
  }
}
