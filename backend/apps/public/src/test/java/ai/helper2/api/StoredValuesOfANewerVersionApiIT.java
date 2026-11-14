package ai.helper2.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class StoredValuesOfANewerVersionApiIT extends ApiTestBase {

  final JdbcClient owner = TestcontainersConfiguration.ownerJdbc();
  UUID changed;
  String extraBefore;

  @AfterEach
  void putTheRecordBack() {
    if (changed != null) {
      owner
          .sql("UPDATE source_record SET extra = CAST(:extra AS jsonb) WHERE id = :id")
          .param("extra", extraBefore)
          .param("id", changed)
          .update();
    }
  }

  @Test
  void aRecordWithValuesThisVersionDoesNotKnowStillOpensItsResource() throws Exception {
    UUID resource =
        owner
            .sql("SELECT id FROM resource WHERE canonical_url = 'https://deepl.com/translator'")
            .query(UUID.class)
            .single();
    changed =
        owner
            .sql("SELECT id FROM source_record WHERE resource_id = :id LIMIT 1")
            .param("id", resource)
            .query(UUID.class)
            .single();
    extraBefore =
        owner
            .sql("SELECT extra::text FROM source_record WHERE id = :id")
            .param("id", changed)
            .query(String.class)
            .single();
    owner
        .sql(
            """
            UPDATE source_record
            SET extra = extra || CAST(:newer AS jsonb)
            WHERE id = :id
            """)
        .param(
            "newer",
            """
            {"popularity": [
               {"kind": "PYPI_DOWNLOADS_MONTHLY", "value": 9, "asOf": "2026-11-01T00:00:00Z"},
               {"kind": "GITHUB_STARS", "value": 7, "asOf": "not a date"},
               {"kind": "GITHUB_STARS", "value": 5, "asOf": "2026-11-01T00:00:00Z"}],
             "pricing": "PER_SEAT",
             "techLevel": "WIZARD",
             "audience": "ROBOTS",
             "eventKind": "PICNIC",
             "eventFormat": "UNDERWATER"}
            """)
        .param("id", changed)
        .update();

    mvc.perform(get("/api/v1/resources/" + resource)).andExpect(status().isOk());
    mvc.perform(get("/resource/" + resource)).andExpect(status().isMovedPermanently());
  }

  @Test
  void aTextWhoseOriginThisVersionDoesNotKnowIsNotShown() throws Exception {
    UUID resource =
        owner
            .sql("SELECT id FROM resource WHERE canonical_url = 'https://deepl.com/translator'")
            .query(UUID.class)
            .single();
    changed =
        owner
            .sql("SELECT id FROM source_record WHERE resource_id = :id LIMIT 1")
            .param("id", resource)
            .query(UUID.class)
            .single();
    extraBefore =
        owner
            .sql("SELECT extra::text FROM source_record WHERE id = :id")
            .param("id", changed)
            .query(String.class)
            .single();
    owner
        .sql(
            """
            UPDATE source_record
            SET extra = extra || CAST('{"descriptionOrigin": "AI_REWRITTEN"}' AS jsonb)
            WHERE id = :id
            """)
        .param("id", changed)
        .update();

    mvc.perform(get("/api/v1/resources/" + resource)).andExpect(status().isOk());
  }
}
