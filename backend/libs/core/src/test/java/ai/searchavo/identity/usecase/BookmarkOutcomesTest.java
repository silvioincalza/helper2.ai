package ai.searchavo.identity.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.identity.Bookmarks;
import ai.searchavo.identity.Bookmarks.Bookmark;
import ai.searchavo.shared.Refused;
import ai.searchavo.shared.UnitOfWork;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BookmarkOutcomesTest {

  private static final int CAP = 500;

  private final UUID user = UUID.randomUUID();
  private final SavedBookmarks saved = new SavedBookmarks();
  private final CountingUnitOfWork unitOfWork = new CountingUnitOfWork();
  private final AddBookmark addBookmark = new AddBookmark(saved, unitOfWork);
  private final MergeLocalBookmarks mergeLocalBookmarks =
      new MergeLocalBookmarks(saved, unitOfWork);

  @Test
  void anAddedBookmarkComesBackAsSaved() {
    Bookmark bookmark = bookmark("https://example.com/one");

    assertThat(addBookmark.add(user, bookmark)).isEqualTo(new AddBookmark.Outcome.Added(bookmark));
    assertThat(saved.locked).containsExactly(user);
  }

  @Test
  void anAdditionWithAnAddressThatIsNotAWebPageWritesNothing() {
    List<String> notWebPages =
        List.of("javascript:alert(1)", "ftp://example.com", "/relative", longAddress());

    assertThat(notWebPages)
        .allSatisfy(address ->
            assertThat(addBookmark.add(user, bookmark(address)))
                .isEqualTo(new AddBookmark.Outcome.InvalidUrl()));
    assertThat(saved.items).isEmpty();
    assertThat(unitOfWork.units).isZero();
  }

  @Test
  void anAdditionOverTheCapIsRefusedInsideItsUnitOfWork() {
    saved.items.addAll(bookmarks(CAP));

    AddBookmark.Outcome outcome = addBookmark.add(user, bookmark("https://example.com/late"));

    assertThat(outcome)
        .isEqualTo(new AddBookmark.Outcome.OverLimit(CAP))
        .isInstanceOf(Refused.class);
    assertThat(unitOfWork.results).containsExactly(outcome);
  }

  @Test
  void theLastAdditionWithinTheCapIsKept() {
    saved.items.addAll(bookmarks(CAP - 1));

    assertThat(addBookmark.add(user, bookmark("https://example.com/last")))
        .isInstanceOf(AddBookmark.Outcome.Added.class);
  }

  @Test
  void aMergeAnswersWithEveryBookmarkOfTheAccount() {
    Bookmark kept = bookmark("https://example.com/kept");
    saved.items.add(kept);
    Bookmark local = bookmark("http://example.com/local");

    assertThat(mergeLocalBookmarks.merge(user, List.of(local)))
        .isEqualTo(new MergeLocalBookmarks.Outcome.Merged(List.of(kept, local)));
  }

  @Test
  void aMergeWithOneAddressThatIsNotAWebPageWritesNothing() {
    List<Bookmark> local =
        List.of(bookmark("https://example.com/fine"), bookmark("ftp://example.com"));

    assertThat(mergeLocalBookmarks.merge(user, local))
        .isEqualTo(new MergeLocalBookmarks.Outcome.InvalidUrl());
    assertThat(saved.items).isEmpty();
    assertThat(unitOfWork.units).isZero();
  }

  @Test
  void aMergeOverTheCapIsRefusedInsideItsUnitOfWork() {
    saved.items.addAll(bookmarks(CAP - 1));

    MergeLocalBookmarks.Outcome outcome =
        mergeLocalBookmarks.merge(
            user, List.of(bookmark("https://example.com/a"), bookmark("https://example.com/b")));

    assertThat(outcome)
        .isEqualTo(new MergeLocalBookmarks.Outcome.OverLimit(CAP))
        .isInstanceOf(Refused.class);
    assertThat(unitOfWork.results).containsExactly(outcome);
  }

  private static String longAddress() {
    return "https://example.com/" + "a".repeat(BookmarkRules.MAX_URL_LENGTH);
  }

  private static List<Bookmark> bookmarks(int count) {
    return IntStream.range(0, count)
        .mapToObj(n -> bookmark("https://example.com/saved/" + n))
        .toList();
  }

  private static Bookmark bookmark(String address) {
    return new Bookmark(UUID.randomUUID(), "a page", URI.create(address), null, Instant.EPOCH);
  }

  private static final class CountingUnitOfWork implements UnitOfWork {

    private int units;
    private final List<Object> results = new ArrayList<>();

    @Override
    public <T> T run(Supplier<T> work) {
      units++;
      T result = work.get();
      results.add(result);
      return result;
    }

    @Override
    public void runWithoutResult(Runnable work) {
      work.run();
    }
  }

  private static final class SavedBookmarks implements Bookmarks {

    private final List<Bookmark> items = new ArrayList<>();
    private final List<UUID> locked = new ArrayList<>();

    @Override
    public void lockForChange(UUID userId) {
      locked.add(userId);
    }

    @Override
    public List<Bookmark> list(UUID userId) {
      return List.copyOf(items);
    }

    @Override
    public void addMissing(UUID userId, List<Bookmark> added) {
      added.stream()
          .filter(a -> items.stream().noneMatch(i -> i.resourceId().equals(a.resourceId())))
          .forEach(items::add);
    }

    @Override
    public long count(UUID userId) {
      return items.size();
    }

    @Override
    public void remove(UUID userId, UUID resourceId) {
      items.removeIf(i -> i.resourceId().equals(resourceId));
    }
  }
}
