package ai.helper2.sources.events;

import java.util.Optional;

public interface EventReader {
  String model();

  String promptVersion();

  Optional<EventReading> read(String title, String pageText);
}
