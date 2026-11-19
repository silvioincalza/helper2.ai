package ai.helper2.scout.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.scout.ListSeed;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcListSeedStoreIT {

  static final Instant T0 = Instant.parse("2026-11-19T21:00:00Z");

  @Autowired JdbcListSeedStore store;

  @AfterEach
  void clean() {
    store.remove("it-acme/awesome-rag");
    store.remove("it-acme/awesome-agents");
  }

  @Test
  void aListIsAddedOnceAndRemovedWithItsRow() {
    ListSeed seed = new ListSeed("it-acme/awesome-rag", "42", T0, "the best RAG list");

    assertThat(store.add(seed)).isTrue();
    assertThat(store.add(new ListSeed("it-acme/awesome-rag", "43", T0, null))).isFalse();

    ListSeed removed = store.remove("it-acme/awesome-rag");
    assertThat(removed).isNotNull();
    assertThat(removed.getAddedBy()).isEqualTo("42");
    assertThat(removed.getAddedAt().truncatedTo(ChronoUnit.MILLIS)).isEqualTo(T0);
    assertThat(removed.getReason()).isEqualTo("the best RAG list");
    assertThat(store.remove("it-acme/awesome-rag")).isNull();
  }

  @Test
  void theListsComeOldestFirstSoTheFirstAddedIsReadFirst() {
    store.add(new ListSeed("it-acme/awesome-rag", "42", T0.plusSeconds(60), null));
    store.add(new ListSeed("it-acme/awesome-agents", "42", T0, null));

    assertThat(store.all())
        .extracting(ListSeed::getRepository)
        .containsSubsequence("it-acme/awesome-agents", "it-acme/awesome-rag");
  }
}
