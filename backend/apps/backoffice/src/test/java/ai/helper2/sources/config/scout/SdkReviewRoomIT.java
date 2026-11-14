package ai.helper2.sources.config.scout;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.sources.github.scout.GitHubSdkCollector;
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
class SdkReviewRoomIT {

  @Autowired ReviewQueue queue;

  @Autowired JdbcClient jdbc;

  final String run = UUID.randomUUID().toString();
  final UUID resource = UUID.randomUUID();
  final String project = "https://pypi.org/project/listed-" + run;
  final String repo = "https://github.com/acme/listed-" + run;

  GitHubSdkCollector.ReviewRoom room;

  @BeforeEach
  void aPythonRepoIndexedThroughPyPi() {
    room = CollectorsConfiguration.reviewRoom(queue, 500);
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
            VALUES (:id, :url, :url, 'Listed', 'SOURCE_PROVIDED', 'LIBRARY', 'BUILD_WITH_AI', 'INDEXED')
            """)
        .param("id", resource)
        .param("url", project)
        .update();
    jdbc.sql("INSERT INTO source (id, mode) VALUES ('pypi', 'INDEXED') ON CONFLICT (id) DO NOTHING")
        .update();
    jdbc.sql(
            """
            INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url,
                                       repository_url, title, type, retrieved_at, fingerprint)
            VALUES (:id, 'pypi', :url, :rid, :url, :url, :repo, 'Listed', 'LIBRARY', now(), 'fp')
            """)
        .param("id", UUID.randomUUID())
        .param("url", project)
        .param("rid", resource)
        .param("repo", repo)
        .update();
  }

  @AfterEach
  void clean() {
    jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", resource).update();
  }

  @Test
  void aRepoListedThroughItsPyPiPackageTakesNoReviewSlot() {
    assertThat(queue.listedAs(repo)).isEmpty();
    assertThat(queue.repositoryListed(repo)).isTrue();
    assertThat(room.known(CanonicalUrl.of(repo))).isTrue();
  }

  @Test
  void aRepoNoRecordNamesIsStillNew() {
    String other = "https://github.com/acme/new-" + run;

    assertThat(queue.repositoryListed(other)).isFalse();
    assertThat(room.known(CanonicalUrl.of(other))).isFalse();
  }
}
