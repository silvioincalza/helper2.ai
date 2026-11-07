package ai.helper2.examples.usecase;

import ai.helper2.examples.ExampleInput;
import ai.helper2.examples.ExampleStatus;
import ai.helper2.examples.HomeExample;
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
 * An administrator changes, pauses or activates an example. A change to what it searches (query,
 * path, expected results) sends it back to PENDING: it is hidden until checked again.
 */
@Service
public class ChangeHomeExample {

  private final HomeExamples examples;
  private final ResourceTitles titles;
  private final AuditTrail audit;

  public ChangeHomeExample(HomeExamples examples, ResourceTitles titles, AuditTrail audit) {
    this.examples = examples;
    this.titles = titles;
    this.audit = audit;
  }

  public sealed interface Outcome {
    record Changed(ExampleView example) implements Outcome {}

    record Invalid(List<String> problems) implements Outcome {}

    record NotFound() implements Outcome {}

    /** Someone changed it first; [current] is what is stored now. */
    record Conflict(ExampleView current) implements Outcome {}

    record Duplicate() implements Outcome {}
  }

  @Transactional
  public Outcome change(
      String adminId,
      UUID id,
      String label,
      String query,
      AudiencePath path,
      List<UUID> expected,
      ExampleStatus status,
      int version) {
    HomeExample current = examples.find(id);
    Outcome outcome = tryChange(id, label, query, path, expected, status, version, current);
    audit.record(
        adminId,
        AuditTrail.Action.CHANGE_HOME_EXAMPLE,
        id.toString(),
        recorded(outcome, current == null ? null : current.getStatus(), status));
    return outcome;
  }

  private Outcome tryChange(
      UUID id,
      String label,
      String query,
      AudiencePath path,
      List<UUID> expected,
      ExampleStatus status,
      int version,
      @Nullable HomeExample current) {
    return switch (ExampleInput.Companion.of(label, query, path, expected)) {
      case ExampleInput.Checked.Invalid invalid -> new Outcome.Invalid(invalid.getProblems());
      case ExampleInput.Checked.Valid valid ->
          store(id, valid.getInput(), status, version, kept(current));
      default -> throw new IllegalStateException();
    };
  }

  /** Expected results already stored: they may have left the catalog since, and still be kept. */
  private static List<UUID> kept(@Nullable HomeExample current) {
    return current == null ? List.of() : current.getExpectedResourceIds();
  }

  private Outcome store(
      UUID id, ExampleInput input, ExampleStatus status, int version, List<UUID> kept) {
    Map<UUID, String> known = titles.of(input.getExpectedResourceIds());
    List<UUID> added =
        input.getExpectedResourceIds().stream().filter(e -> !kept.contains(e)).toList();
    List<String> unknown = Expected.unknown(added, known);
    if (!unknown.isEmpty()) {
      return new Outcome.Invalid(unknown);
    }
    return switch (examples.change(id, input, status, version)) {
      case HomeExamples.Changed.Done done ->
          new Outcome.Changed(ListAdminHomeExamples.view(done.getExample(), known));
      case HomeExamples.Changed.Conflict conflict ->
          new Outcome.Conflict(ListAdminHomeExamples.view(conflict.getCurrent(), titles));
      case HomeExamples.Changed.NotFound n -> new Outcome.NotFound();
      case HomeExamples.Changed.Duplicate d -> new Outcome.Duplicate();
      default -> throw new IllegalStateException();
    };
  }

  /** PAUSED or ACTIVATED when the status is what changed, CHANGED otherwise. */
  private static String recorded(Outcome outcome, ExampleStatus before, ExampleStatus after) {
    return switch (outcome) {
      case Outcome.Changed c when before != after ->
          after == ExampleStatus.PAUSED ? "PAUSED" : "ACTIVATED";
      case Outcome.Changed c -> "CHANGED";
      case Outcome.Invalid i -> "INVALID";
      case Outcome.NotFound n -> "NOT_FOUND";
      case Outcome.Conflict c -> "CONFLICT";
      case Outcome.Duplicate d -> "DUPLICATE";
    };
  }
}
