package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.ReviewQueue.Kind;
import ai.helper2.catalog.ReviewQueue.Status;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcReviewQueueIT {

  static final String SOURCE = "review-queue-it-" + UUID.randomUUID();

  @Autowired ReviewQueue queue;

  @Autowired JdbcClient jdbc;

  final String run = UUID.randomUUID().toString();
  final UUID resource = UUID.randomUUID();
  final String repo = "https://github.com/acme/listed-" + run;
  final String pkg = "https://npmjs.com/package/listed-" + run;

  String candidateUrl(String name) {
    return "https://review-queue.example/" + run + "/" + name;
  }

  String details(String title) {
    return "{\"sourceId\":\"" + SOURCE + "\",\"title\":\"" + title + "\"}";
  }

  @AfterEach
  void clean() {
    jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", resource).update();
    jdbc.sql("DELETE FROM review_item WHERE candidate_url LIKE :prefix")
        .param("prefix", "https://review-queue.example/" + run + "/%")
        .update();
    jdbc.sql("DELETE FROM resource WHERE canonical_url LIKE :prefix")
        .param("prefix", "https://review-queue.example/" + run + "/%")
        .update();
  }

  private void repositoryWithAPackageRecord() {
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
            VALUES (:id, :url, :url, :title, 'SOURCE_PROVIDED', 'LIBRARY', 'BUILD_WITH_AI', 'OBSERVED')
            """)
        .param("id", resource)
        .param("url", repo)
        .param("title", "Listed " + run)
        .update();
    jdbc.sql("INSERT INTO source (id, mode) VALUES ('npm', 'LIVE') ON CONFLICT (id) DO NOTHING")
        .update();
    jdbc.sql(
            """
            INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url,
                                       repository_url, title, type, retrieved_at, fingerprint)
            VALUES (:id, 'npm', :url, :rid, :url, :url, :repo, 'listed', 'LIBRARY', now(), 'fp')
            """)
        .param("id", UUID.randomUUID())
        .param("url", pkg)
        .param("rid", resource)
        .param("repo", repo)
        .update();
  }

  @Test
  void aCandidateAtTheAddressOfARecordIsAlreadyListedUnderItsResource() {
    repositoryWithAPackageRecord();

    ReviewQueue.Listed listed = new ReviewQueue.Listed(resource, "Listed " + run);
    assertThat(queue.listedAs(pkg)).contains(listed);
    assertThat(queue.listedAs(repo)).contains(listed);
    assertThat(queue.candidateKnown(Kind.RESOURCE_CANDIDATE, pkg)).isTrue();
    assertThat(queue.proposeCandidate(Kind.RESOURCE_CANDIDATE, pkg, "New", "{}")).isFalse();
    assertThat(queue.listedAs("https://nowhere.example/" + run)).isEmpty();
  }

  @Test
  void aCandidateIsProposedOnceAndFoundOpen() {
    String url = candidateUrl("tool");

    assertThat(queue.proposeCandidate(Kind.RESOURCE_CANDIDATE, url, "new", details("Tool")))
        .isTrue();
    assertThat(queue.proposeCandidate(Kind.RESOURCE_CANDIDATE, url, "again", details("Tool")))
        .isFalse();

    ReviewQueue.Item item = queue.openCandidate(Kind.RESOURCE_CANDIDATE, url).orElseThrow();
    assertThat(item.status()).isEqualTo(Status.OPEN);
    assertThat(item.reason()).isEqualTo("new");
    assertThat(queue.find(item.id())).contains(item);
    assertThat(queue.candidateKnown(Kind.RESOURCE_CANDIDATE, url)).isTrue();
  }

  @Test
  void aLinkAlreadyInTheCatalogIsNoCandidate() {
    String url = candidateUrl("known");
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
            VALUES (:id, :url, :url, 'Known', 'SOURCE_PROVIDED', 'PRODUCT', 'USE_AI', 'INDEXED')
            """)
        .param("id", UUID.randomUUID())
        .param("url", url)
        .update();

    assertThat(queue.proposeCandidate(Kind.RESOURCE_CANDIDATE, url, "new", details("Known")))
        .isFalse();
    assertThat(queue.listedAs(url)).isPresent();
  }

  @Test
  void aCandidateChangesOnlyFromTheDetailsTheEditorSaw() {
    String url = candidateUrl("edited");
    queue.proposeCandidate(Kind.RESOURCE_CANDIDATE, url, "new", details("Before"));
    ReviewQueue.Item item = queue.openCandidate(Kind.RESOURCE_CANDIDATE, url).orElseThrow();

    assertThat(queue.updateCandidate(item.id(), details("Other"), "edited", details("After")))
        .isFalse();
    assertThat(queue.updateCandidate(item.id(), item.details(), "edited", details("After")))
        .isTrue();
    assertThat(queue.find(item.id()).orElseThrow().reason()).isEqualTo("edited");
  }

  @Test
  void theCandidatesOfTodayAreCountedPerSource() {
    queue.proposeCandidate(Kind.RESOURCE_CANDIDATE, candidateUrl("a"), "new", details("A"));
    queue.proposeCandidate(Kind.RESOURCE_CANDIDATE, candidateUrl("b"), "new", details("B"));

    assertThat(queue.candidatesOpenedToday(Kind.RESOURCE_CANDIDATE, SOURCE)).isEqualTo(2);
    assertThat(queue.candidatesOpenedToday(Kind.EXAMPLE_CANDIDATE, SOURCE)).isZero();
  }
}
