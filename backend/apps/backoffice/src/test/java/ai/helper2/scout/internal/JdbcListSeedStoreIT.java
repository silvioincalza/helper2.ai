package ai.helper2.scout.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.scout.ListKind;
import ai.helper2.scout.ListSeed;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcListSeedStoreIT {

  static final Instant T0 = Instant.parse("2026-11-19T21:00:00Z");
  static final String PAGE = "https://it-blog.acme.dev/Best-AI-Tools?page=2";

  @Autowired JdbcListSeedStore store;

  @AfterEach
  void clean() {
    store.remove("it-acme/awesome-rag");
    store.remove("it-acme/awesome-agents");
    store.remove(PAGE);
  }

  static ListSeed repository(String name, String by, Instant at, String reason) {
    return new ListSeed(ListKind.GITHUB_REPOSITORY, name, by, at, reason);
  }

  @Test
  void aListIsAddedOnceAndRemovedWithItsRow() {
    ListSeed seed = repository("it-acme/awesome-rag", "42", T0, "the best RAG list");

    assertThat(store.add(seed)).isTrue();
    assertThat(store.add(repository("it-acme/awesome-rag", "43", T0, null))).isFalse();

    ListSeed removed = store.remove("it-acme/awesome-rag");
    assertThat(removed).isNotNull();
    assertThat(removed.getKind()).isEqualTo(ListKind.GITHUB_REPOSITORY);
    assertThat(removed.getAddedBy()).isEqualTo("42");
    assertThat(removed.getAddedAt().truncatedTo(ChronoUnit.MILLIS)).isEqualTo(T0);
    assertThat(removed.getReason()).isEqualTo("the best RAG list");
    assertThat(store.remove("it-acme/awesome-rag")).isNull();
  }

  @Test
  void theListsComeOldestFirstSoTheFirstAddedIsReadFirst() {
    store.add(repository("it-acme/awesome-rag", "42", T0.plusSeconds(60), null));
    store.add(repository("it-acme/awesome-agents", "42", T0, null));

    assertThat(store.all())
        .extracting(ListSeed::getAddress)
        .containsSubsequence("it-acme/awesome-agents", "it-acme/awesome-rag");
  }

  @Test
  void aWebPageIsStoredWithItsAddressAndTheLastTimeItsReadWasTried() {
    assertThat(store.add(new ListSeed(ListKind.WEB_PAGE, PAGE, "42", T0, "a roundup"))).isTrue();
    assertThat(page().getReadTriedAt()).isNull();

    store.readTried(PAGE, T0.plusSeconds(3600));

    ListSeed read = page();
    assertThat(read.getKind()).isEqualTo(ListKind.WEB_PAGE);
    assertThat(read.getId())
        .isEqualTo(new ListSeed(ListKind.WEB_PAGE, PAGE, "1", T0, null).getId());
    assertThat(read.getReadTriedAt().truncatedTo(ChronoUnit.MILLIS))
        .isEqualTo(T0.plusSeconds(3600));
  }

  @Test
  void theTableRefusesAnAddressThatDoesNotFitItsKind() {
    assertThatThrownBy(
            () -> store.add(new ListSeed(ListKind.WEB_PAGE, "it-acme/x", "42", T0, null)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> store.add(repository("https://it-blog.acme.dev/list", "42", T0, null)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() ->
            store.add(
                new ListSeed(ListKind.WEB_PAGE, "http://it-blog.acme.dev/list", "42", T0, null)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() ->
            store.add(new ListSeed(ListKind.WEB_PAGE, "https://10.0.0.7/list", "42", T0, null)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private ListSeed page() {
    return store.all().stream().filter(s -> s.getAddress().equals(PAGE)).findFirst().orElseThrow();
  }
}
