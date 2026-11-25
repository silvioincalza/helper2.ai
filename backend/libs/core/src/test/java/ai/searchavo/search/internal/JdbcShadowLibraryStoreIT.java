package ai.searchavo.search.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.searchavo.TestcontainersConfiguration;
import ai.searchavo.search.ShadowLibraries;
import ai.searchavo.search.ShadowLibrary;
import ai.searchavo.search.ShadowLibraryNames;
import ai.searchavo.search.ShadowLibraryStore;
import ai.searchavo.testing.AsBackOffice;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AsBackOffice
class JdbcShadowLibraryStoreIT {

  static final Instant ADDED = Instant.parse("2026-11-18T03:04:05Z");
  static final String NAME = "store-it-mirror";

  @Autowired ShadowLibraryStore store;
  @Autowired ShadowLibraries libraries;

  @AfterEach
  void removeWhatThisTestAdded() {
    store.remove(NAME);
  }

  @Test
  void theFirstListIsInEveryDatabaseAndTheSearchReadsItAtStartup() {
    assertThat(store.names())
        .as("the names a list starts from before its first read")
        .containsAll(ShadowLibraryNames.SEEDED);
    assertThat(
            store.all().stream()
                .filter(l -> l.getAddedBy().equals("migration"))
                .map(ShadowLibrary::getName))
        .containsExactlyInAnyOrderElementsOf(ShadowLibraryNames.SEEDED);
    assertThat(store.all())
        .filteredOn(l -> l.getName().equals("libgen"))
        .extracting(ShadowLibrary::getAddedBy)
        .containsExactly("migration");
    assertThat(libraries.isShadowLibrary("ka.z-lib.fm")).isTrue();
  }

  @Test
  void aNameIsAddedOnceAndRemovedWithWhatItWas() {
    ShadowLibrary library = new ShadowLibrary(NAME, "owner", ADDED, "pirated books");

    assertThat(store.add(library)).isTrue();
    assertThat(store.add(new ShadowLibrary(NAME, "other", ADDED, null))).isFalse();
    assertThat(store.all()).contains(library);
    assertThat(store.remove(NAME)).isEqualTo(library);
    assertThat(store.remove(NAME)).isNull();
  }

  @Test
  void theDatabaseRefusesANameThatIsNotOneHostLabel() {
    assertThatThrownBy(() -> store.add(new ShadowLibrary("libgen.rs", "owner", ADDED, null)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
