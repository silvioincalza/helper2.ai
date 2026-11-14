package ai.helper2.examples.usecase;

import ai.helper2.examples.HomeExample;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ExampleView(HomeExample example, List<Expected> expected) {
  public record Expected(UUID id, @Nullable String title) {}
}
