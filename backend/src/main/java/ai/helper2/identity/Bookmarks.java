package ai.helper2.identity;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Outbound port: the resources each account saved. The rules (valid URLs, the per-account cap) are
 * the use cases' (identity.usecase).
 */
public interface Bookmarks {

  record Bookmark(UUID resourceId, String title, URI url, String type, Instant savedAt) {}

  /** Newest first. */
  List<Bookmark> list(UUID userId);

  /** Inserts each resource the account has not saved yet; one it has keeps its first savedAt. */
  void addMissing(UUID userId, List<Bookmark> items);

  long count(UUID userId);

  void remove(UUID userId, UUID resourceId);
}
