package ai.helper2.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.internal.ReviewEvidenceFixture;
import ai.helper2.catalog.usecase.ChangeRelationshipTarget;
import ai.helper2.catalog.usecase.ChangeRelationshipTarget.Outcome;
import ai.helper2.catalog.usecase.ChangeRelationshipTarget.Target;
import ai.helper2.shared.Refused;
import ai.helper2.shared.ResourceType;
import ai.helper2.testing.AsBackOffice;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AsBackOffice
class ChangeRelationshipTargetIT {

  private static final String NEW_URL = "https://www.change-target-it.example/";
  private static final String NEW_CANONICAL = "https://change-target-it.example";

  @Autowired ChangeRelationshipTarget change;
  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate tx;

  private final String admin = "change-target-" + UUID.randomUUID();
  private final Target added = new Target.Added("LangChain", NEW_URL, ResourceType.LIBRARY);
  private ReviewEvidenceFixture fixture;

  @BeforeEach
  void insert() {
    fixture = new ReviewEvidenceFixture(jdbc).insert();
  }

  @AfterEach
  void clean() {
    fixture.delete();
    jdbc.sql("DELETE FROM resource WHERE canonical_url = :url")
        .param("url", NEW_CANONICAL)
        .update();
  }

  @Test
  void aResourceAddedForATargetIsKeptWithTheLink() {
    assertThat(change.change(fixture.relationshipItem, added, admin))
        .isEqualTo(new Outcome.Changed());

    assertThat(addedResources()).isOne();
    assertThat(audit()).hasSize(2).first().isEqualTo("ADD_RESOURCE ADDED");
  }

  @Test
  void aRefusedLinkUndoesTheResourceAddedForIt() {
    Outcome notARelationship = change.change(fixture.validationItem, added, admin);
    Outcome notFound = change.change(UUID.randomUUID(), added, admin);

    assertThat(notARelationship)
        .isEqualTo(new Outcome.NotARelationship())
        .isInstanceOf(Refused.class);
    assertThat(notFound).isEqualTo(new Outcome.NotFound()).isInstanceOf(Refused.class);
    assertThat(addedResources()).isZero();
    assertThat(audit()).isEmpty();
  }

  @Test
  void aRefusedLinkInsideACallersTransactionLeavesTheDecisionToTheCaller() {
    Outcome outcome = tx.execute(status -> change.change(fixture.validationItem, added, admin));

    assertThat(outcome).isEqualTo(new Outcome.NotARelationship());
    assertThat(addedResources()).isOne();
  }

  @Test
  void aResourceThatCannotBeAddedIsRefusedBeforeAnyWrite() {
    assertThat(change.change(fixture.relationshipItem, resource(" ", NEW_URL, "LIBRARY"), admin))
        .isEqualTo(new Outcome.InvalidResourceTitle(300));
    assertThat(
            change.change(
                fixture.relationshipItem,
                resource("LangChain", "http://x.example", "LIBRARY"),
                admin))
        .isEqualTo(new Outcome.InvalidResourceUrl());
    assertThat(
            change.change(fixture.relationshipItem, resource("LangChain", NEW_URL, "NEWS"), admin))
        .isEqualTo(new Outcome.InvalidResourceType(ResourceType.NEWS));
    assertThat(change.change(fixture.relationshipItem, new Target.Renamed(" "), admin))
        .isEqualTo(new Outcome.InvalidName(120));

    assertThat(addedResources()).isZero();
    assertThat(audit()).isEmpty();
  }

  @Test
  void aTargetTheItemCannotTakeIsRefused() {
    assertThat(
            change.change(fixture.relationshipItem, new Target.Existing(UUID.randomUUID()), admin))
        .isEqualTo(new Outcome.UnknownTarget());
    assertThat(
            change.change(fixture.relationshipItem, new Target.Existing(fixture.resource), admin))
        .isEqualTo(new Outcome.SelfLink());
    decide();
    assertThat(change.change(fixture.relationshipItem, new Target.Renamed("LangChain 2"), admin))
        .isEqualTo(new Outcome.NotOpen());

    assertThat(audit()).isEmpty();
  }

  private static Target resource(String title, String url, String type) {
    return new Target.Added(title, url, ResourceType.valueOf(type));
  }

  private void decide() {
    jdbc.sql(
            """
            UPDATE review_item SET status = 'REJECTED', resolved_at = now(), resolved_by = 'x'
            WHERE id = :id
            """)
        .param("id", fixture.relationshipItem)
        .update();
  }

  private long addedResources() {
    return jdbc.sql("SELECT count(*) FROM resource WHERE canonical_url = :url")
        .param("url", NEW_CANONICAL)
        .query(Long.class)
        .single();
  }

  private List<String> audit() {
    return jdbc.sql(
            "SELECT action || ' ' || outcome FROM admin_audit WHERE admin_id = :a ORDER BY at, id")
        .param("a", admin)
        .query(String.class)
        .list();
  }
}
