package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.KnownTarget;
import ai.helper2.catalog.usecase.LinkKnownRelationshipTargets;
import ai.helper2.testing.AsBackOffice;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AsBackOffice
class JdbcRelationshipTargetsIT {

  @Autowired LinkKnownRelationshipTargets link;
  @Autowired JdbcClient jdbc;

  final List<UUID> created = new ArrayList<>();
  final String name = "tool" + UUID.randomUUID().toString().substring(0, 8);

  @AfterEach
  void clean() {
    created.forEach(id -> jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", id).update());
  }

  private UUID resource(String title, String origin, String type) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
            VALUES (:id, :url, :url, :title, 'SOURCE_PROVIDED', :type, 'BUILD_WITH_AI', :origin)
            """)
        .param("id", id)
        .param("url", "https://relationship-targets.example/" + id)
        .param("title", title)
        .param("type", type)
        .param("origin", origin)
        .update();
    created.add(id);
    return id;
  }

  private UUID candidate(UUID from, String target, String origin, String itemStatus) {
    UUID relationship = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO relationship (id, from_resource_id, to_title, kind, verification, origin)
            VALUES (:id, :from, :title, 'INTEGRATES_WITH', 'UNVERIFIED', :origin)
            """)
        .param("id", relationship)
        .param("from", from)
        .param("title", target)
        .param("origin", origin)
        .update();
    jdbc.sql(
            """
            INSERT INTO review_item (id, kind, status, resource_id, relationship_id, reason, resolved_at)
            VALUES (:id, 'RELATIONSHIP_CANDIDATE', :status, :from, :rel, 'Unverified relationship',
                    CASE WHEN :status = 'OPEN' THEN NULL ELSE now() END)
            """)
        .param("id", UUID.randomUUID())
        .param("status", itemStatus)
        .param("from", from)
        .param("rel", relationship)
        .update();
    return relationship;
  }

  private Map<String, Object> state(UUID relationship) {
    return jdbc.sql(
            """
            SELECT rel.to_resource_id AS target, rel.verification, i.status, i.resolved_by, i.resolution_note
            FROM relationship rel JOIN review_item i ON i.relationship_id = rel.id
            WHERE rel.id = :id
            """)
        .param("id", relationship)
        .query()
        .singleRow();
  }

  @Test
  void anOpenCandidateWhoseTargetIsNowInTheCatalogIsLinkedAndItsItemSuperseded() {
    UUID from = resource("Uses " + name, "INDEXED", "MCP_SERVER");
    UUID byTitle = candidate(from, " " + name.toUpperCase() + " ", "AI_EXTRACTED", "OPEN");
    UUID byRepositoryName = candidate(from, name + " cli", "AI_EXTRACTED", "OPEN");
    UUID titled = resource(name, "CURATED", "PRODUCT");
    UUID repository = resource("an-owner/" + name + "-cli", "INDEXED", "AGENT");

    assertThat(link.link()).isGreaterThanOrEqualTo(2);

    assertThat(state(byTitle))
        .containsEntry("target", titled)
        .containsEntry("verification", "VERIFIED")
        .containsEntry("status", "SUPERSEDED")
        .containsEntry("resolved_by", LinkKnownRelationshipTargets.BY)
        .containsEntry("resolution_note", LinkKnownRelationshipTargets.NOTE);
    assertThat(state(byRepositoryName))
        .containsEntry("target", repository)
        .containsEntry("status", "SUPERSEDED");
    assertThat(link.link()).isZero();
  }

  @Test
  void aCandidateWithNoTargetOrMoreThanOneStaysOpen() {
    UUID from = resource("Uses " + name, "INDEXED", "LIBRARY");
    UUID unknown = candidate(from, name + " unknown", "AI_EXTRACTED", "OPEN");
    UUID ambiguous = candidate(from, name, "AI_EXTRACTED", "OPEN");
    resource("first-owner/" + name, "INDEXED", "LIBRARY");
    resource("second-owner/" + name, "INDEXED", "AGENT");

    link.link();

    for (UUID relationship : List.of(unknown, ambiguous)) {
      assertThat(state(relationship))
          .containsEntry("target", null)
          .containsEntry("verification", "UNVERIFIED")
          .containsEntry("status", "OPEN");
    }
  }

  @Test
  void aResourceIsNeverItsOwnTargetAndTheOtherOneIsTaken() {
    UUID from = resource("an-owner/" + name, "INDEXED", "LIBRARY");
    UUID alone = candidate(from, name, "AI_EXTRACTED", "OPEN");

    link.link();
    assertThat(state(alone)).containsEntry("target", null).containsEntry("status", "OPEN");

    UUID other = resource("another-owner/" + name, "INDEXED", "LIBRARY");
    link.link();
    assertThat(state(alone)).containsEntry("target", other).containsEntry("status", "SUPERSEDED");
  }

  @Test
  void whatAPersonMustDecideOrHasDecidedIsLeftAlone() {
    UUID from = resource("Uses " + name, "INDEXED", "LIBRARY");
    resource("an-owner/" + name, "INDEXED", "LIBRARY");
    UUID selfDeclared = candidate(from, name, "SELF_DECLARED", "OPEN");
    UUID other = resource("Also uses " + name, "INDEXED", "LIBRARY");
    UUID rejected = candidate(other, name, "AI_EXTRACTED", "REJECTED");
    UUID third = resource("Renamed " + name, "INDEXED", "LIBRARY");
    UUID renamed = candidate(third, name, "AI_EXTRACTED", "OPEN");
    jdbc.sql("UPDATE relationship SET target_origin = 'CURATED' WHERE id = :id")
        .param("id", renamed)
        .update();

    link.link();

    assertThat(state(selfDeclared)).containsEntry("target", null).containsEntry("status", "OPEN");
    assertThat(state(rejected)).containsEntry("target", null).containsEntry("status", "REJECTED");
    assertThat(state(renamed)).containsEntry("target", null).containsEntry("status", "OPEN");
  }

  @Test
  void anOpenCandidateThatNamesAKnownVendorIsConfirmedAndItsItemSuperseded() {
    UUID from = resource("Uses " + name, "INDEXED", "LIBRARY");
    UUID vendor = candidate(from, "Anthropic's", "AI_EXTRACTED", "OPEN");
    UUID selfDeclared = candidate(from, "Cohere", "SELF_DECLARED", "OPEN");
    UUID notAVendor = candidate(from, "Anthropic Console " + name, "AI_EXTRACTED", "OPEN");

    assertThat(link.link()).isGreaterThanOrEqualTo(1);

    assertThat(state(vendor))
        .containsEntry("target", null)
        .containsEntry("verification", "VERIFIED")
        .containsEntry("status", "SUPERSEDED")
        .containsEntry("resolved_by", LinkKnownRelationshipTargets.BY)
        .containsEntry("resolution_note", LinkKnownRelationshipTargets.VENDOR_NOTE);
    assertThat(targetKind(vendor)).isEqualTo("VENDOR");
    assertThat(state(selfDeclared)).containsEntry("status", "OPEN");
    assertThat(state(notAVendor)).containsEntry("status", "OPEN");
    assertThat(targetKind(notAVendor)).isNull();
  }

  @Test
  void anOpenCandidateThatNamesAKnownServiceIsConfirmedAsAService() {
    String service = "Service " + name;
    UUID known = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO known_target (id, kind, name, added_by) VALUES (:id, 'SERVICE', :name, 'test')
            """)
        .param("id", known)
        .param("name", service)
        .update();
    jdbc.sql("INSERT INTO known_target_name (name_key, target_id, name) VALUES (:key, :id, :name)")
        .param("key", KnownTarget.key(service))
        .param("id", known)
        .param("name", service)
        .update();
    try {
      UUID from = resource("Uses " + name, "INDEXED", "LIBRARY");
      UUID named = candidate(from, "  " + service.toUpperCase() + "'s", "AI_EXTRACTED", "OPEN");

      link.link();

      assertThat(state(named))
          .containsEntry("target", null)
          .containsEntry("verification", "VERIFIED")
          .containsEntry("status", "SUPERSEDED")
          .containsEntry("resolution_note", LinkKnownRelationshipTargets.SERVICE_NOTE);
      assertThat(targetKind(named)).isEqualTo("SERVICE");
    } finally {
      jdbc.sql("DELETE FROM known_target WHERE id = :id").param("id", known).update();
    }
  }

  @Test
  void anOpenCandidateThatNamesAVendorIsTheVendorNotAPackageOfThatName() {
    UUID from = resource("Uses " + name, "INDEXED", "AGENT");
    UUID vendor = candidate(from, "Cohere", "AI_EXTRACTED", "OPEN");
    resource("cohere", "INDEXED", "LIBRARY");

    link.link();

    assertThat(state(vendor))
        .containsEntry("target", null)
        .containsEntry("verification", "VERIFIED")
        .containsEntry("resolution_note", LinkKnownRelationshipTargets.VENDOR_NOTE);
    assertThat(targetKind(vendor)).isEqualTo("VENDOR");
  }

  @Test
  void anOpenCandidateThatNamesAProductIsNotLinkedToAnMcpServerTitledLikeIt() {
    UUID from = resource("Uses " + name, "INDEXED", "AGENT");
    UUID product = candidate(from, name, "AI_EXTRACTED", "OPEN");
    resource(name, "INDEXED", "MCP_SERVER");

    link.link();

    assertThat(state(product)).containsEntry("target", null).containsEntry("status", "OPEN");
  }

  private String targetKind(UUID relationship) {
    return jdbc.sql("SELECT target_kind FROM relationship WHERE id = :id")
        .param("id", relationship)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  @Test
  void aNameUnderFourCharactersIsNeverLinked() {
    String shortName = "x" + UUID.randomUUID().toString().substring(0, 2);
    UUID from = resource("Uses " + name, "INDEXED", "LIBRARY");
    UUID candidate = candidate(from, shortName, "AI_EXTRACTED", "OPEN");
    resource(shortName, "INDEXED", "LIBRARY");

    link.link();

    assertThat(state(candidate)).containsEntry("target", null).containsEntry("status", "OPEN");
  }
}
