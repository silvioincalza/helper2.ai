package ai.helper2.examples.usecase;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.examples.DraftRules;
import ai.helper2.examples.DraftSource;
import ai.helper2.examples.DraftSources;
import ai.helper2.examples.DraftsUnavailable;
import ai.helper2.examples.ExampleDraft;
import ai.helper2.examples.ExampleDrafter;
import ai.helper2.examples.HomeExample;
import ai.helper2.examples.HomeExamples;
import ai.helper2.examples.config.ExamplesProperties;
import ai.helper2.identity.AuditTrail;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * An administrator asks a model to draft examples (ADR 0049 §6). The model gets public facts about
 * well-known catalog resources that no example expects yet; each draft that passes {@link
 * DraftRules} waits in the review queue as EXAMPLE_CANDIDATE. Nothing reaches the home page before
 * a person accepts it and the quality check passes it.
 */
@Service
public class DraftHomeExamples {

  private final HomeExamples examples;
  private final DraftSources sources;
  private final ExampleDrafter drafter;
  private final ReviewQueue queue;
  private final AuditTrail audit;
  private final ObjectMapper json;
  private final int sourcesPerRun;

  public DraftHomeExamples(
      HomeExamples examples,
      DraftSources sources,
      ExampleDrafter drafter,
      ReviewQueue queue,
      AuditTrail audit,
      ObjectMapper json,
      ExamplesProperties properties) {
    this.examples = examples;
    this.sources = sources;
    this.drafter = drafter;
    this.queue = queue;
    this.audit = audit;
    this.json = json;
    this.sourcesPerRun = properties.draftSources();
  }

  /** What a draft run gave: how many drafts wait in the review queue, and the model. */
  public record Drafted(int count, String model) {}

  /** Throws {@link DraftsUnavailable} (audited) when there is no model, budget or answer. */
  @Transactional(noRollbackFor = DraftsUnavailable.class)
  public Drafted draft(String adminId) {
    List<HomeExample> pool = examples.all(null);
    Set<UUID> expected =
        pool.stream().flatMap(e -> e.getExpectedResourceIds().stream()).collect(Collectors.toSet());
    List<DraftSource> given = sources.wellKnown(sourcesPerRun, expected);
    List<ExampleDraft> drafts;
    try {
      drafts = DraftRules.INSTANCE.keep(drafter.draft(given), given, pool, drafter.getModel());
    } catch (DraftsUnavailable e) {
      audit.record(adminId, AuditTrail.Action.DRAFT_HOME_EXAMPLES, null, e.getReason().name());
      throw e;
    }
    drafts.forEach(this::open);
    audit.record(adminId, AuditTrail.Action.DRAFT_HOME_EXAMPLES, null, "DRAFTED " + drafts.size());
    return new Drafted(drafts.size(), drafter.getModel());
  }

  private void open(ExampleDraft draft) {
    queue.open(
        ReviewQueue.Kind.EXAMPLE_CANDIDATE,
        draft.getResourceId(),
        "Drafted example: " + draft.getInput().getLabel(),
        json.writeValueAsString(
            Map.of(
                "label", draft.getInput().getLabel(),
                "query", draft.getInput().getQuery(),
                "path", draft.getInput().getPath().name(),
                "model", draft.getModel())),
        ReviewQueue.Links.NONE);
  }
}
