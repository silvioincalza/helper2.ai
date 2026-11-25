package ai.searchavo.catalog;

import java.util.Optional;

public interface JobMarkers {
  Optional<String> marker(String name);

  void mark(String name, String value);

  String lastMigration();
}
