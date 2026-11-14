package ai.helper2.platform.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.testing.AsBackOffice;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AsBackOffice
class JdbcRuntimeSettingStoreIT {

  static final Instant AT = Instant.parse("2026-11-05T10:00:00Z");

  @Autowired JdbcClient jdbc;

  @Autowired TransactionTemplate transactions;

  final List<String> names = new ArrayList<>();

  JdbcRuntimeSettingStore store() {
    return new JdbcRuntimeSettingStore(jdbc);
  }

  String name() {
    String name = "test." + UUID.randomUUID();
    names.add(name);
    return name;
  }

  @AfterEach
  void deleteOwnRows() {
    names.forEach(
        n -> jdbc.sql("DELETE FROM runtime_setting WHERE name = :n").param("n", n).update());
  }

  @Test
  void theNewestChangeWinsAndAResetHasNoValue() {
    JdbcRuntimeSettingStore store = store();
    String name = name();

    var first = store.append(name, 10L, 0, "1", AT, "load test");
    var second = store.append(name, 20L, first.version(), "1", AT.plusSeconds(1), null);
    var reset = store.append(name, null, second.version(), "2", AT.plusSeconds(2), "back");

    assertThat(store.newest().get(name)).isEqualTo(reset);
    assertThat(reset.value()).isNull();
    assertThat(store.history(name, 10)).containsExactly(reset, second, first);
    assertThat(store.history(name, 2)).containsExactly(reset, second);
    assertThat(store.version()).isGreaterThanOrEqualTo(reset.version());
    assertThat(first.reason()).isEqualTo("load test");
  }

  @Test
  void aStaleVersionAppendsNothing() {
    JdbcRuntimeSettingStore store = store();
    String name = name();
    var first = store.append(name, 10L, 0, "1", AT, null);

    assertThat(store.append(name, 30L, 0, "2", AT, null)).as("made on version 0").isNull();
    assertThat(store.history(name, 10)).containsExactly(first);
  }

  @Test
  void twoReplicasChangingAtOnceStoreOneChange() throws Exception {
    JdbcRuntimeSettingStore store = store();
    String name = name();
    CountDownLatch start = new CountDownLatch(1);
    Callable<Boolean> change =
        () -> {
          start.await();
          return transactions.execute(t -> store.append(name, 5L, 0, "1", AT, null) != null);
        };
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<Boolean> a = pool.submit(change);
      Future<Boolean> b = pool.submit(change);
      start.countDown();
      assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
    }
    assertThat(store.history(name, 10)).hasSize(1);
  }

  @Test
  void aChangeIsNeverUpdated() {
    String name = name();
    store().append(name, 10L, 0, "1", AT, null);

    assertThatThrownBy(() ->
            jdbc.sql("UPDATE runtime_setting SET value = 99 WHERE name = :n")
                .param("n", name)
                .update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void aValueBelowZeroIsRefusedByTheTable() {
    String name = name();
    assertThatThrownBy(() -> store().append(name, -1L, 0, "1", AT, null))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void thePurgeKeepsEachSettingsNewestChange() {
    JdbcRuntimeSettingStore store = store();
    String old = name();
    Instant longAgo = Instant.parse("2001-01-01T00:00:00Z");
    var a = store.append(old, 1L, 0, "1", longAgo, null);
    var b = store.append(old, 2L, a.version(), "1", longAgo.plusSeconds(1), null);

    assertThat(store.deleteBefore(Instant.parse("2002-01-01T00:00:00Z"))).isEqualTo(1);
    assertThat(store.history(old, 10)).containsExactly(b);
  }
}
