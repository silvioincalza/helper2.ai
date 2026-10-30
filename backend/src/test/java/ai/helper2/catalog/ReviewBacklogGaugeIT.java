package ai.helper2.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.usecase.ResolveReviewItem;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The review backlog gauge counts the items waiting for a person: OPEN ones only. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReviewBacklogGaugeIT {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ReviewQueue queue;

    @Autowired
    ResolveReviewItem resolve;

    @Autowired
    MeterRegistry meters;

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM review_item WHERE candidate_url LIKE 'https://example.com/backlog-%'").update();
    }

    private double gauge() {
        return meters.get("helper2.review.open_items").gauge().value();
    }

    @Test
    void countsOpenItems_notDecidedOnes() {
        double before = gauge();
        queue.proposeCandidate(ReviewQueue.Kind.COURSE_CANDIDATE, "https://example.com/backlog-1", "New course", "");
        queue.proposeCandidate(ReviewQueue.Kind.COURSE_CANDIDATE, "https://example.com/backlog-2", "New course", "");
        assertThat(gauge()).isEqualTo(before + 2);

        ReviewQueue.Item first = queue.list(ReviewQueue.Status.OPEN, 0, 100).items().stream()
            .filter(i -> "New course".equals(i.reason())).findFirst().orElseThrow();
        resolve.resolve(first.id(), ReviewQueue.Status.REJECTED, "admin", "not a course");

        assertThat(gauge()).isEqualTo(before + 1);
    }
}
