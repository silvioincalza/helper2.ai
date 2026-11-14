package ai.helper2.catalog;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.testing.AsBackOffice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AsBackOffice
class ReviewItemSchemaTest {

  @Autowired JdbcClient jdbc;

  @AfterEach
  void clean() {
    jdbc.sql("DELETE FROM review_item WHERE candidate_url LIKE 'https://demo.example/review/%'")
        .update();
  }

  private void reviewItem(String kind, String details, String note) {
    jdbc.sql(
            """
            INSERT INTO review_item (id, kind, candidate_url, reason, details, note)
            VALUES (gen_random_uuid(), :kind, 'https://demo.example/review/' || gen_random_uuid(), 'r',
                    CAST(:details AS jsonb), :note)
            """)
        .param("kind", kind)
        .param("details", details)
        .param("note", note)
        .update();
  }

  @Test
  void reviewItemDetailsAreAJsonObjectOrANoteByKind() {
    reviewItem("RESOURCE_CANDIDATE", "{\"title\":\"x\"}", null);
    reviewItem("COURSE_CANDIDATE", null, "URL: https://demo.example/course");
    assertThatThrownBy(() -> reviewItem("RESOURCE_CANDIDATE", "[1]", null))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> reviewItem("RESOURCE_CANDIDATE", null, "plain text"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> reviewItem("COURSE_CANDIDATE", "{}", null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
