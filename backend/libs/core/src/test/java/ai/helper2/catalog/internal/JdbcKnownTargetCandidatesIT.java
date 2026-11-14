package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.KnownTarget;
import ai.helper2.catalog.KnownTargetCandidates;
import ai.helper2.catalog.KnownTargetCandidates.Candidate;
import ai.helper2.catalog.KnownTargetCandidates.Proposed;
import ai.helper2.catalog.ReviewDecisions;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.testing.AsBackOffice;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AsBackOffice
class JdbcKnownTargetCandidatesIT {

  static final int NO_CAP = 1_000_000;

  @Autowired KnownTargetCandidates candidates;
  @Autowired ReviewDecisions decisions;
  @Autowired JdbcClient jdbc;

  final String tag = UUID.randomUUID().toString().substring(0, 8);
  final String source = "kt-" + tag;
  final String registry = "kr-" + tag;
  final List<UUID> resources = new ArrayList<>();

  @BeforeEach
  void sources() {
    for (String id : List.of(source, registry)) {
      jdbc.sql("INSERT INTO source (id, mode) VALUES (:id, 'INDEXED')").param("id", id).update();
    }
  }

  @AfterEach
  void clean() {
    resources.forEach(
        id -> jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", id).update());
    jdbc.sql("DELETE FROM source WHERE id IN (:a, :b)")
        .param("a", source)
        .param("b", registry)
        .update();
    jdbc.sql(
            "DELETE FROM candidate_decision d WHERE d.kind = 'KNOWN_TARGET_CANDIDATE'"
                + " AND d.candidate_key IN (SELECT "
                + UrlKeys.keyOf("i.details ->> 'key'")
                + " FROM review_item i WHERE i.kind = 'KNOWN_TARGET_CANDIDATE'"
                + " AND i.details ->> 'key' LIKE :tag)")
        .param("tag", "%" + tag)
        .update();
    jdbc.sql(
            "DELETE FROM review_item WHERE kind = 'KNOWN_TARGET_CANDIDATE' AND details ->> 'key' LIKE :tag")
        .param("tag", "%" + tag)
        .update();
  }

  private String owner(String name) {
    return name + "-" + tag;
  }

  private void owns(String sourceId, String owner, int count) {
    for (int i = 0; i < count; i++) {
      UUID resource = UUID.randomUUID();
      String title = owner + "/project-" + i;
      String url = "https://owners.example/" + resource;
      jdbc.sql(
              """
              INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
              VALUES (:id, :url, :url, :title, 'SOURCE_PROVIDED', 'MODEL', 'BUILD_WITH_AI', 'INDEXED')
              """)
          .param("id", resource)
          .param("url", url)
          .param("title", title)
          .update();
      resources.add(resource);
      jdbc.sql(
              """
              INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title,
                                         type, retrieved_at, fingerprint)
              VALUES (:id, :source, :url, :rid, :url, :url, :title, 'MODEL', now(), 'fp')
              """)
          .param("id", UUID.randomUUID())
          .param("source", sourceId)
          .param("url", url)
          .param("rid", resource)
          .param("title", title)
          .update();
      jdbc.sql("UPDATE resource SET popularity = :popularity WHERE id = :id")
          .param("popularity", i / 10.0)
          .param("id", resource)
          .update();
    }
  }

  private Proposed propose(int perDay) {
    return candidates.proposeOwners(List.of(source), 5, perDay);
  }

  private List<Map<String, Object>> proposals(String owner) {
    return jdbc.sql(
            """
            SELECT id, status, reason, details ->> 'name' AS name, details ->> 'kind' AS kind,
                   details ->> 'rule' AS rule, (details ->> 'count')::int AS count,
                   jsonb_array_length(details -> 'examples') AS examples,
                   details -> 'examples' -> 0 ->> 'title' AS first_example
            FROM review_item WHERE kind = 'KNOWN_TARGET_CANDIDATE' AND details ->> 'key' = :key
            """)
        .param("key", owner.toLowerCase())
        .query()
        .listOfRows();
  }

  private int openedToday() {
    return jdbc.sql(
            """
            SELECT count(*) FROM review_item
            WHERE kind = 'KNOWN_TARGET_CANDIDATE'
              AND created_at >= date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
            """)
        .query(Integer.class)
        .single();
  }

  @Test
  void anOwnerOfEnoughResourcesIsProposedAsAVendorWithItsCountAndItsMostPopularResources() {
    owns(source, owner("Acme"), 6);
    owns(source, owner("small"), 4);

    Proposed proposed = propose(NO_CAP);

    assertThat(proposed).isEqualTo(new Proposed(1, 0));
    assertThat(proposals(owner("small"))).isEmpty();
    Map<String, Object> item = proposals(owner("Acme")).getFirst();
    assertThat(item)
        .containsEntry("status", "OPEN")
        .containsEntry("name", owner("Acme"))
        .containsEntry("kind", "VENDOR")
        .containsEntry("rule", "OWNER")
        .containsEntry("count", 6)
        .containsEntry("examples", 5)
        .containsEntry("first_example", owner("Acme") + "/project-5")
        .containsEntry("reason", "Owner of 6 GitHub or Hugging Face resources: " + owner("Acme"));
    assertThat(candidates.of((UUID) item.get("id")))
        .contains(new Candidate(owner("Acme"), KnownTarget.Kind.VENDOR, List.of()));
  }

  @Test
  void aRecordOfASourceWhereAnyoneRegistersANameDoesNotCount() {
    owns(registry, owner("io.github.somebody"), 9);
    owns(source, owner("partly"), 3);
    owns(registry, owner("partly"), 3);

    assertThat(propose(NO_CAP)).isEqualTo(new Proposed(0, 0));
    assertThat(proposals(owner("io.github.somebody"))).isEmpty();
    assertThat(proposals(owner("partly"))).isEmpty();
  }

  @Test
  void anOwnerWhoseNameIsAKnownTargetIsNotProposed() {
    UUID target = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO known_target (id, kind, name, added_by) VALUES (:id, 'VENDOR', :name, 'test')")
        .param("id", target)
        .param("name", "Known " + tag)
        .update();
    jdbc.sql("INSERT INTO known_target_name (name_key, target_id, name) VALUES (:key, :id, :name)")
        .param("key", owner("known"))
        .param("id", target)
        .param("name", owner("known"))
        .update();
    try {
      owns(source, owner("Known"), 5);

      assertThat(propose(NO_CAP)).isEqualTo(new Proposed(0, 0));
      assertThat(proposals(owner("Known"))).isEmpty();
    } finally {
      jdbc.sql("DELETE FROM known_target WHERE id = :id").param("id", target).update();
    }
  }

  @Test
  void aNameIsProposedOnceAndNeverAgainAfterADecisionEvenWhenItsItemIsGone() {
    owns(source, owner("Once"), 5);
    assertThat(propose(NO_CAP).opened()).isEqualTo(1);
    assertThat(propose(NO_CAP)).isEqualTo(new Proposed(0, 0));
    UUID item = (UUID) proposals(owner("Once")).getFirst().get("id");

    decisions.decide(item, ReviewQueue.Status.REJECTED, "admin", "a person");
    jdbc.sql("DELETE FROM review_item WHERE id = :id").param("id", item).update();

    assertThat(propose(NO_CAP)).isEqualTo(new Proposed(0, 0));
    assertThat(proposals(owner("Once"))).isEmpty();
    assertThat(
            jdbc.sql(
                    "SELECT status FROM candidate_decision WHERE kind = 'KNOWN_TARGET_CANDIDATE'"
                        + " AND candidate_key = "
                        + UrlKeys.keyOf("CAST(:key AS text)"))
                .param("key", owner("once"))
                .query(String.class)
                .single())
        .isEqualTo("REJECTED");
    jdbc.sql(
            "DELETE FROM candidate_decision WHERE kind = 'KNOWN_TARGET_CANDIDATE' AND candidate_key = "
                + UrlKeys.keyOf("CAST(:key AS text)"))
        .param("key", owner("once"))
        .update();
  }

  @Test
  void theDailyCapOpensTheOwnersOfTheMostResourcesAndHoldsTheRestBack() {
    owns(source, owner("big"), 7);
    owns(source, owner("mid"), 6);
    owns(source, owner("low"), 5);

    Proposed first = propose(openedToday() + 1);

    assertThat(first).isEqualTo(new Proposed(1, 2));
    assertThat(proposals(owner("big"))).hasSize(1);
    assertThat(proposals(owner("mid"))).isEmpty();

    assertThat(propose(openedToday())).isEqualTo(new Proposed(0, 2));
    assertThat(propose(0)).isEqualTo(new Proposed(0, 2));
    assertThat(propose(NO_CAP)).isEqualTo(new Proposed(2, 0));
  }

  @Test
  void ownersThatDifferOnlyInCaseAreOneNameShownAsMostOfItsRecordsWriteIt() {
    owns(source, owner("NVIDIA"), 3);
    owns(source, owner("nvidia"), 2);

    assertThat(propose(NO_CAP).opened()).isEqualTo(1);
    Map<String, Object> item = proposals(owner("nvidia")).getFirst();
    assertThat(item).containsEntry("name", owner("NVIDIA")).containsEntry("count", 5);
  }
}
