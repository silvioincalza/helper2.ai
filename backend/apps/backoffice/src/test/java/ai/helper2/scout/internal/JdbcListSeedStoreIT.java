package ai.helper2.scout.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.scout.ListKind;
import ai.helper2.scout.ListOrigin;
import ai.helper2.scout.ListReading;
import ai.helper2.scout.ListSeed;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcListSeedStoreIT {

  static final Instant T0 = Instant.parse("2026-11-19T21:00:00Z");
  static final String PAGE = "https://it-blog.acme.dev/Best-AI-Tools?page=2";

  @Autowired JdbcListSeedStore store;
  @Autowired JdbcClient jdbcClient;
  @Autowired ObjectMapper json;

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

    store.readStarted(PAGE, T0);
    store.readStarted(PAGE, T0.plusSeconds(3600));
    assertThat(page().getReadFailures()).isEqualTo(2);
    store.readSucceeded(PAGE, new ListReading(T0, List.of(), List.of(), List.of(), 0, 0));

    ListSeed read = page();
    assertThat(read.getReadFailures()).isZero();
    assertThat(read.getKind()).isEqualTo(ListKind.WEB_PAGE);
    assertThat(read.getId())
        .isEqualTo(new ListSeed(ListKind.WEB_PAGE, PAGE, "1", T0, null).getId());
    assertThat(read.getReadTriedAt().truncatedTo(ChronoUnit.MILLIS))
        .isEqualTo(T0.plusSeconds(3600));
  }

  @Test
  void theLastReadOfAWebPageIsKeptAcrossARestartReplacedOnTheNextReadAndGoesWithTheSeed() {
    store.add(new ListSeed(ListKind.WEB_PAGE, PAGE, "42", T0, null));
    ListReading first =
        new ListReading(
            T0,
            List.of("Best AI tools", "Engines"),
            List.of(
                new ListReading.Entry(
                    "infiniflow/ragflow", "Engines", "Engines", "Ragflow - RAG engine.", false)),
            List.of(
                new ListReading.Link(
                    "https://vectorly.io", "Vectorly", "Engines", "Engines", "Vectorly - search.")),
            12,
            7);
    store.readStarted(PAGE, T0);
    store.readSucceeded(PAGE, first);

    ListReading afterRestart = new JdbcListSeedStore(jdbcClient, json).lastReads().get(PAGE);
    assertThat(afterRestart).usingRecursiveComparison().ignoringFields("readAt").isEqualTo(first);
    assertThat(afterRestart.getReadAt().truncatedTo(ChronoUnit.MILLIS)).isEqualTo(T0);

    ListReading second =
        new ListReading(T0.plusSeconds(60), List.of("Tools"), List.of(), List.of(), 3, 0);
    store.readSucceeded(PAGE, second);
    assertThat(store.lastReads().get(PAGE).getHeadings()).containsExactly("Tools");

    store.remove(PAGE);
    assertThat(store.lastReads()).doesNotContainKey(PAGE);
  }

  @Test
  void aGitHubListHasNoStoredRead() {
    store.add(repository("it-acme/awesome-rag", "42", T0, null));

    store.readSucceeded(
        "it-acme/awesome-rag", new ListReading(T0, List.of(), List.of(), List.of(), 0, 0));

    assertThat(store.lastReads()).doesNotContainKey("it-acme/awesome-rag");
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

  @Test
  void uc030Br003AListDiscoveryAddedKeepsItsOriginAndOnlyAWebPageCanHaveIt() {
    ListSeed found =
        new ListSeed(
            ListKind.WEB_PAGE, PAGE, "discovery-coding", T0, null, null, 0, ListOrigin.DISCOVERY);

    assertThat(store.add(found)).isTrue();

    assertThat(store.all())
        .filteredOn(s -> s.getAddress().equals(PAGE))
        .singleElement()
        .satisfies(s -> assertThat(s.getOrigin()).isEqualTo(ListOrigin.DISCOVERY));
    assertThatThrownBy(() ->
            store.add(
                new ListSeed(
                    ListKind.GITHUB_REPOSITORY,
                    "it-acme/awesome-rag",
                    "discovery-coding",
                    T0,
                    null,
                    null,
                    0,
                    ListOrigin.DISCOVERY)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
