package ai.helper2.catalog;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface Resources {
  record Titled(UUID id, String title) {}

  record WellKnown(
      UUID id, String title, String type, String audience, @Nullable String description) {}

  record Indexable(UUID id, String title, Instant lastModified) {}

  Optional<ResourceRow> findResource(UUID id);

  List<ResourceRow> findResources(Collection<UUID> ids);

  List<RecordRow> recordsOf(UUID resourceId);

  Map<UUID, List<RecordRow>> recordsOf(Collection<UUID> resourceIds);

  Optional<UUID> ownerOfRecordUrl(UUID id);

  List<UUID> resourcesOfRecords(Collection<UUID> recordIds);

  Map<UUID, String> titles(Collection<UUID> ids);

  Optional<Titled> atUrl(String canonicalUrl);

  List<WellKnown> wellKnown(int limit, Collection<UUID> excluding);

  List<Indexable> indexable(Collection<String> types, int promptMin, int limit);

  List<UUID> upcomingEvents(Instant today, @Nullable Instant until, int limit);

  int countUpcomingEvents(Instant today, @Nullable Instant until);

  List<UUID> recentlyPublished(
      Instant since, Instant now, Set<String> types, Set<DataOrigin> origins, int limit);

  int countRecentlyPublished(
      Instant since, Instant now, Set<String> types, Set<DataOrigin> origins);

  Map<UUID, Instant> firstDiscovered(Collection<UUID> ids);

  Map<String, Long> discoveredSince(Instant since);

  long discoveredInIndexSince(Instant since, Set<DataOrigin> origins);
}
