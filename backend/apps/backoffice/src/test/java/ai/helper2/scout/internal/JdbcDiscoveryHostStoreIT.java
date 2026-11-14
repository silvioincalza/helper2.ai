package ai.helper2.scout.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.scout.DiscoveryHost;
import ai.helper2.scout.HostKind;
import ai.helper2.scout.SeedDiscoveryHosts;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcDiscoveryHostStoreIT {

  static final Instant T0 = Instant.parse("2026-11-06T22:00:00Z");

  @Autowired JdbcDiscoveryHostStore store;

  @AfterEach
  void clean() {
    store.remove("it-news.example");
    if (store.all().stream().noneMatch(h -> h.getHost().equals("g2.com"))) {
      store.add(new DiscoveryHost("g2.com", HostKind.DIRECTORY, "migration", T0, null));
    }
  }

  @Test
  void theMigrationSeedsTheListsTheRulesWereWrittenFor() {
    assertThat(store.lists()).isEqualTo(SeedDiscoveryHosts.LISTS);
  }

  @Test
  void aHostIsAddedOnceWhateverItsKindAndRemovedWithItsRow() {
    DiscoveryHost host =
        new DiscoveryHost("it-news.example", HostKind.WRITING, "42", T0, "a news site");

    assertThat(store.add(host)).isTrue();
    assertThat(store.add(new DiscoveryHost("it-news.example", HostKind.DIRECTORY, "43", T0, null)))
        .isFalse();
    assertThat(store.lists().getWriting()).contains("it-news.example");
    assertThat(store.lists().getDirectory()).doesNotContain("it-news.example");

    DiscoveryHost removed = store.remove("it-news.example");
    assertThat(removed).isNotNull();
    assertThat(removed.getKind()).isEqualTo(HostKind.WRITING);
    assertThat(removed.getAddedBy()).isEqualTo("42");
    assertThat(removed.getAddedAt().truncatedTo(ChronoUnit.MILLIS)).isEqualTo(T0);
    assertThat(removed.getReason()).isEqualTo("a news site");
    assertThat(store.remove("it-news.example")).isNull();
  }

  @Test
  void aSeededHostCanBeRemoved() {
    assertThat(store.remove("g2.com")).isNotNull();
    assertThat(store.lists().getDirectory()).doesNotContain("g2.com");
  }
}
