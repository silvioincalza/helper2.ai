package ai.helper2.identity;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface Bookmarks {

  record Bookmark(UUID resourceId, String title, URI url, String type, Instant savedAt) {}

  List<Bookmark> list(UUID userId);

  void addMissing(UUID userId, List<Bookmark> items);

  long count(UUID userId);

  void remove(UUID userId, UUID resourceId);
}
