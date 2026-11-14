package ai.helper2.platform.settings;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public interface RuntimeSettingStore {
  record Change(
      long version,
      String name,
      @Nullable Long value,
      String changedBy,
      Instant changedAt,
      @Nullable String reason) {}

  long version();

  Map<String, Change> newest();

  List<Change> history(String name, int limit);

  @Nullable Change append(
      String name,
      @Nullable Long value,
      long expectedVersion,
      String changedBy,
      Instant at,
      @Nullable String reason);

  int deleteBefore(Instant cutoff);
}
