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
    return ResponseEntity.ok(dto(addBookmark.add(signIn.userId(), bookmark(body))));
  }

  @Override
  public ResponseEntity<List<BookmarkDto>> mergeLocalBookmarks(List<BookmarkRequestDto> body) {
    return ResponseEntity.ok(
        mergeLocalBookmarks
            .merge(signIn.userId(), body.stream().map(BookmarksController::bookmark).toList())
            .stream()
            .map(BookmarksController::dto)
            .toList());
  }

  @Override
  public ResponseEntity<Void> deleteBookmark(UUID resourceId) {
    removeBookmark.remove(signIn.userId(), resourceId);
    return ResponseEntity.noContent().build();
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
