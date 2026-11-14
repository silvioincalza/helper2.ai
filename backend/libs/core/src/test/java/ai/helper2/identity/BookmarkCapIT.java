package ai.helper2.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.identity.Bookmarks.Bookmark;
import ai.helper2.identity.usecase.AddBookmark;
import ai.helper2.identity.usecase.MergeLocalBookmarks;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BookmarkCapIT {

  private static final Duration PATIENCE = Duration.ofSeconds(20);
  private static final int CAP = 500;

  @Autowired AddBookmark addBookmark;
  @Autowired MergeLocalBookmarks mergeLocalBookmarks;
  @Autowired Bookmarks bookmarks;
  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate tx;

  private final UUID user = UUID.randomUUID();

  @BeforeEach
  void anAccountOneBookmarkBelowTheCap() {
    jdbc.sql("INSERT INTO app_user (id, provider, subject) VALUES (:id, 'test', :subject)")
        .param("id", user)
        .param("subject", user.toString())
        .update();
    jdbc.sql(
            """
            INSERT INTO bookmark (user_id, resource_id, title, url)
            SELECT :u, gen_random_uuid(), 'saved', 'https://example.com/' || n
            FROM generate_series(1, :count) AS n
            """)
        .param("u", user)
        .param("count", CAP - 1)
        .update();
  }

  private static Bookmark another() {
    UUID id = UUID.randomUUID();
    return new Bookmark(
        id, "one more", URI.create("https://example.com/" + id), null, Instant.now());
  }

  private void untilWaitingForALock(CompletableFuture<?> write) throws Exception {
    long deadline = System.nanoTime() + PATIENCE.toNanos();
    while (System.nanoTime() < deadline) {
      if (write.isDone()) {
        throw new AssertionError("the second addition ended without waiting for the first");
      }
      int waiting =
          jdbc.sql(
                  """
                  SELECT count(*) FROM pg_stat_activity
                  WHERE datname = current_database() AND wait_event_type = 'Lock'
                  """)
              .query(Integer.class)
              .single();
      if (waiting > 0) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("no addition waited for the other");
  }

  @Test
  void twoAdditionsAtOnceCannotPassTheCapTogether() throws Exception {
    CountDownLatch added = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    CompletableFuture<Void> first =
        CompletableFuture.runAsync(() ->
            tx.executeWithoutResult(status -> {
              addBookmark.add(user, another());
              added.countDown();
              try {
                assertThat(release.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
              }
            }));
    assertThat(added.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();
    CompletableFuture<Bookmark> second =
        CompletableFuture.supplyAsync(() -> addBookmark.add(user, another()));
    untilWaitingForALock(second);
    release.countDown();
    first.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);

    assertThatThrownBy(() -> second.get(PATIENCE.toSeconds(), TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasMessageContaining("At most " + CAP + " bookmarks");
    assertThat(bookmarks.count(user)).isEqualTo(CAP);
  }

  @Test
  void aMergeThatWouldPassTheCapKeepsNothingOfItself() {
    assertThatThrownBy(() -> mergeLocalBookmarks.merge(user, List.of(another(), another())))
        .hasMessageContaining("At most " + CAP + " bookmarks");

    assertThat(bookmarks.count(user)).isEqualTo(CAP - 1);
  }
}
