package ai.helper2.identity.web;

import ai.helper2.api.common.model.ResourceTypeDto;
import ai.helper2.api.generated.BookmarksApi;
import ai.helper2.api.generated.model.BookmarkDto;
import ai.helper2.api.generated.model.BookmarkRequestDto;
import ai.helper2.identity.Bookmarks.Bookmark;
import ai.helper2.identity.usecase.AddBookmark;
import ai.helper2.identity.usecase.ListBookmarks;
import ai.helper2.identity.usecase.MergeLocalBookmarks;
import ai.helper2.identity.usecase.RemoveBookmark;
import ai.helper2.platform.BadRequestException;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class BookmarksController implements BookmarksApi {

  private final RequireSignIn signIn;
  private final ListBookmarks listBookmarks;
  private final AddBookmark addBookmark;
  private final RemoveBookmark removeBookmark;
  private final MergeLocalBookmarks mergeLocalBookmarks;

  BookmarksController(
      RequireSignIn signIn,
      ListBookmarks listBookmarks,
      AddBookmark addBookmark,
      RemoveBookmark removeBookmark,
      MergeLocalBookmarks mergeLocalBookmarks) {
    this.signIn = signIn;
    this.listBookmarks = listBookmarks;
    this.addBookmark = addBookmark;
    this.removeBookmark = removeBookmark;
    this.mergeLocalBookmarks = mergeLocalBookmarks;
  }

  @Override
  public ResponseEntity<List<BookmarkDto>> listBookmarks() {
    return ResponseEntity.ok(
        listBookmarks.list(signIn.userId()).stream().map(BookmarksController::dto).toList());
  }

  @Override
  public ResponseEntity<BookmarkDto> addBookmark(UUID resourceId, BookmarkRequestDto body) {
    if (!resourceId.equals(body.getResourceId())) {
      throw new BadRequestException("resourceId in the path and the body differ");
    }
    return switch (addBookmark.add(signIn.userId(), bookmark(body))) {
      case AddBookmark.Outcome.Added added -> ResponseEntity.ok(dto(added.bookmark()));
      case AddBookmark.Outcome.InvalidUrl invalid -> throw invalidUrl();
      case AddBookmark.Outcome.OverLimit over -> throw overLimit(over.limit());
    };
  }

  @Override
  public ResponseEntity<List<BookmarkDto>> mergeLocalBookmarks(List<BookmarkRequestDto> body) {
    List<Bookmark> local = body.stream().map(BookmarksController::bookmark).toList();
    return switch (mergeLocalBookmarks.merge(signIn.userId(), local)) {
      case MergeLocalBookmarks.Outcome.Merged merged ->
          ResponseEntity.ok(merged.bookmarks().stream().map(BookmarksController::dto).toList());
      case MergeLocalBookmarks.Outcome.InvalidUrl invalid -> throw invalidUrl();
      case MergeLocalBookmarks.Outcome.OverLimit over -> throw overLimit(over.limit());
    };
  }

  @Override
  public ResponseEntity<Void> deleteBookmark(UUID resourceId) {
    removeBookmark.remove(signIn.userId(), resourceId);
    return ResponseEntity.noContent().build();
  }

  private static BadRequestException invalidUrl() {
    return new BadRequestException("invalid bookmark url");
  }

  private static BadRequestException overLimit(int limit) {
    return new BadRequestException("At most " + limit + " bookmarks per account.");
  }

  private static Bookmark bookmark(BookmarkRequestDto r) {
    return new Bookmark(
        r.getResourceId(),
        r.getTitle(),
        r.getUrl(),
        r.getType() == null ? null : r.getType().getValue(),
        null);
  }

  private static BookmarkDto dto(Bookmark b) {
    return new BookmarkDto(b.resourceId(), b.title(), b.url(), b.savedAt().atOffset(ZoneOffset.UTC))
        .type(b.type() == null ? null : ResourceTypeDto.fromValue(b.type()));
  }
}
