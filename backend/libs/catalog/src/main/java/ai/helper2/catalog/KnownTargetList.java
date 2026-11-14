package ai.helper2.catalog;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface KnownTargetList {

  record Entry(
      UUID id,
      KnownTarget.Kind kind,
      String name,
      List<String> otherNames,
      String addedBy,
      Instant addedAt,
      KnownTarget.Origin origin,
      int relationships) {

    public List<String> names() {
      return java.util.stream.Stream.concat(java.util.stream.Stream.of(name), otherNames.stream())
          .toList();
    }
  }

  void lockForChange();

  List<Entry> all();

  @Nullable Entry find(UUID id);

  Map<String, UUID> holders(Collection<String> keys);

  void add(
      UUID id,
      KnownTarget.Kind kind,
      List<String> names,
      String by,
      Instant at,
      KnownTarget.Origin origin);

  void replace(UUID id, KnownTarget.Kind kind, List<String> names);

  void remove(UUID id);

  int release(Collection<String> keys, String reason);

  void setKind(Collection<String> keys, KnownTarget.Kind kind);
}
