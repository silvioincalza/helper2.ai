package ai.helper2.examples.usecase;

import ai.helper2.examples.ExampleInput;
import ai.helper2.examples.ExampleOrigin;
import ai.helper2.examples.HomeExamples;
import ai.helper2.examples.ResourceTitles;
import ai.helper2.identity.AuditTrail;
import ai.helper2.shared.AudiencePath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An administrator adds an example. It waits for its first check (PENDING) and is shown only once
 * it passes. Every attempt is in the audit trail, in the same transaction as the change.
 */
@Service
public class AddHomeExample {

  /** The UI ships in English only (ADR 0015): every example is stored for this locale. */
  public static final String LOCALE = "en";

  private final HomeExamples examples;
  private final ResourceTitles titles;
  private final AuditTrail audit;

  public AddHomeExample(HomeExamples examples, ResourceTitles titles, AuditTrail audit) {
    this.examples = examples;
    this.titles = titles;
    this.audit = audit;
  }

  public sealed interface Outcome {
    record Added(ExampleView example) implements Outcome {}

    /** The texts or the expected results break a rule, in words for the administrator. */
    record Invalid(List<String> problems) implements Outcome {}

    record Duplicate() implements Outcome {}
  }

  @Transactional
  public Outcome add(
      String adminId, String label, String query, AudiencePath path, List<UUID> expected) {
    return add(adminId, label, query, path, expected, ExampleOrigin.CURATED, null);
  }

  /** [origin] and [model] say who wrote it: CURATED by a person, AI_DRAFTED with its model. */
  @Transactional
  public Outcome add(
      String adminId,
      String label,
      String query,
      AudiencePath path,
      List<UUID> expected,
      ExampleOrigin origin,
      @Nullable String model) {
    Outcome outcome = tryAdd(label, query, path, expected, origin, model);
    UUID target = outcome instanceof Outcome.Added a ? a.example().example().getId() : null;
    audit.record(
        adminId,
        AuditTrail.Action.ADD_HOME_EXAMPLE,
        target == null ? null : target.toString(),
        switch (outcome) {
          case Outcome.Added a -> "ADDED";
          case Outcome.Invalid i -> "INVALID";
          case Outcome.Duplicate d -> "DUPLICATE";
        });
    return outcome;
  }

  private Outcome tryAdd(
      String label,
      String query,
      AudiencePath path,
      List<UUID> expected,
      ExampleOrigin origin,
      @Nullable String model) {
    return switch (ExampleInput.Companion.of(label, query, path, expected)) {
      case ExampleInput.Checked.Invalid invalid -> new Outcome.Invalid(invalid.getProblems());
      case ExampleInput.Checked.Valid valid -> store(valid.getInput(), origin, model);
      default -> throw new IllegalStateException();
    };
  }

  private Outcome store(ExampleInput input, ExampleOrigin origin, @Nullable String model) {
    Map<UUID, String> known = titles.of(input.getExpectedResourceIds());
    List<String> unknown = Expected.unknown(input.getExpectedResourceIds(), known);
    if (!unknown.isEmpty()) {
      return new Outcome.Invalid(unknown);
    }
    return switch (examples.add(input, origin, model, LOCALE)) {
      case HomeExamples.Added.Done done ->
          new Outcome.Added(ListAdminHomeExamples.view(done.getExample(), known));
      case HomeExamples.Added.Duplicate d -> new Outcome.Duplicate();
      default -> throw new IllegalStateException();
    };
  }
}
