package ai.helper2.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.usecase.ResolveReviewItem;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** V3 migration (ADR 0032 §8): COURSE is a resource type, and course pages Scout finds wait in review, once per URL. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CourseCandidateReviewTest {

    static final String URL = "https://coursera.org/learn/test-candidate-course";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ReviewQueue queue;

    @Autowired
    ResolveReviewItem resolve;

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM review_item WHERE candidate_url LIKE 'https://coursera.org/learn/test-%'").update();
        jdbc.sql("DELETE FROM resource WHERE canonical_url LIKE 'https://coursera.org/learn/test-%'").update();
    }

    @Test
    void courseIsAResourceTypeAndUnknownTypesStayRejected() {
        jdbc.sql("""
                INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
                VALUES (:id, :u, :u, 'A course', 'DEMO', 'COURSE', 'BOTH', 'DEMO')
                """).param("id", UUID.randomUUID()).param("u", "https://coursera.org/learn/test-type").update();
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
                VALUES (:id, :u, :u, 'Nope', 'DEMO', 'WEBINAR', 'BOTH', 'DEMO')
                """).param("id", UUID.randomUUID()).param("u", "https://coursera.org/learn/test-bad").update())
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aPageIsProposedOnceEvenAfterItWasRejected() {
        assertThat(queue.proposeCandidate(ReviewQueue.Kind.COURSE_CANDIDATE, URL, "New course", "URL: " + URL)).isTrue();
        assertThat(queue.proposeCandidate(ReviewQueue.Kind.COURSE_CANDIDATE, URL, "New course", "URL: " + URL)).isFalse();

        ReviewQueue.Item item = queue.list(ReviewQueue.Status.OPEN, 0, 100).items().stream()
            .filter(i -> i.kind() == ReviewQueue.Kind.COURSE_CANDIDATE && i.details().contains(URL)).findFirst().orElseThrow();
        assertThat(item.resourceId()).isNull();
        resolve.resolve(item.id(), ReviewQueue.Status.REJECTED, "admin", "not a course");

        assertThat(queue.proposeCandidate(ReviewQueue.Kind.COURSE_CANDIDATE, URL, "New course", "URL: " + URL)).isFalse();
    }

    @Test
    void aPageAlreadyInTheCatalogIsNotProposed() {
        String url = "https://coursera.org/learn/test-already-there";
        jdbc.sql("""
                INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
                VALUES (:id, :u, :u, 'Already there', 'DEMO', 'COURSE', 'BOTH', 'DEMO')
                """).param("id", ResourceId.of(CanonicalUrl.of(url)).getValue()).param("u", url).update();
        assertThat(queue.proposeCandidate(ReviewQueue.Kind.COURSE_CANDIDATE, url, "New course", "URL: " + url)).isFalse();
    }
}
