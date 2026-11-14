package ai.helper2.examples.usecase;

import ai.helper2.examples.DraftQueue;
import ai.helper2.examples.DraftRules;
import ai.helper2.examples.DraftSource;
import ai.helper2.examples.DraftSources;
import ai.helper2.examples.DraftsUnavailable;
import ai.helper2.examples.ExampleDraft;
import ai.helper2.examples.ExampleDrafter;
import ai.helper2.examples.HomeExample;
import ai.helper2.examples.HomeExamples;
import ai.helper2.examples.TakenText;
import ai.helper2.examples.UnitOfWork;
import ai.helper2.platform.AuditTrail;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DraftHomeExamples {

  private final HomeExamples examples;
  private final DraftSources sources;
  private final ExampleDrafter drafter;
  private final DraftQueue queue;
  private final AuditTrail audit;
  private final UnitOfWork transaction;
  private final int sourcesPerRun;

  public DraftHomeExamples(
      HomeExamples examples,
      DraftSources sources,
      ExampleDrafter drafter,
      DraftQueue queue,
      AuditTrail audit,
      UnitOfWork transaction,
      int sourcesPerRun) {
    this.examples = examples;
    this.sources = sources;
    this.drafter = drafter;
    this.queue = queue;
    this.audit = audit;
    this.transaction = transaction;
    this.sourcesPerRun = sourcesPerRun;
  }

  public record Drafted(int count, String model) {}

  public Drafted draft(String adminId) {
    List<HomeExample> pool = examples.all(null);
    List<DraftSource> given = sources.wellKnown(sourcesPerRun, covered(pool));
    List<ExampleDraft> drafts;
    try {
      drafts =
          DraftRules.INSTANCE.keep(drafter.draft(given), given, taken(pool), drafter.getModel());
    } catch (DraftsUnavailable e) {
      audit.record(adminId, AuditTrail.Action.DRAFT_HOME_EXAMPLES, null, e.getReason().name());
      throw e;
    }
    transaction.run(() -> {
      drafts.forEach(queue::open);
      audit.record(
          adminId, AuditTrail.Action.DRAFT_HOME_EXAMPLES, null, "DRAFTED " + drafts.size());
    });
    return new Drafted(drafts.size(), drafter.getModel());
  }

  private Set<UUID> covered(List<HomeExample> pool) {
    return Stream.concat(
            pool.stream().flatMap(e -> e.getExpectedResourceIds().stream()),
            queue.waitingResources().stream())
        .collect(Collectors.toSet());
  }

  private List<TakenText> taken(List<HomeExample> pool) {
    return Stream.concat(
            pool.stream().map(e -> new TakenText(e.getLabel(), e.getQuery())),
            queue.waiting().stream().map(d -> new TakenText(d.getLabel(), d.getQuery())))
        .toList();
  }
}
