package ai.helper2.platform.settings;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PublicAppSettings {
  record Publication(String build, Instant publishedAt, List<SettingsGroup> groups) {
    public Publication {
      groups = List.copyOf(groups);
    }
  }

  void publish(String build, Instant at, List<SettingsGroup> groups);

  Optional<Publication> newest();
}
