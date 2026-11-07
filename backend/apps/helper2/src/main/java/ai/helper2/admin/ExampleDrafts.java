package ai.helper2.admin;

import ai.helper2.shared.AudiencePath;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: the home example a model drafted, as DraftHomeExamples left it in a review item.
 */
public interface ExampleDrafts {

  record Draft(String label, String query, AudiencePath path, String model) {}

  /** The draft in a review item's details; empty when they are not a readable draft. */
  Optional<Draft> read(@Nullable String details);
}
