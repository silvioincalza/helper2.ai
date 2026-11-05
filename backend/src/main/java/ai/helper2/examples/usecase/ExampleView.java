package ai.helper2.examples.usecase;

import ai.helper2.examples.HomeExample;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** An example as the back office shows it: with the titles of its expected results. */
public record ExampleView(HomeExample example, List<Expected> expected) {

  /** An expected result; [title] is null when the catalog no longer has it. */
  public record Expected(UUID id, @Nullable String title) {}
}
