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
import ai.helper2.examples.TakenText;
import ai.helper2.examples.config.ExamplesProperties;
import ai.helper2.identity.AuditTrail;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * An administrator asks a model to draft examples (ADR 0049 §6). The model gets public facts about
 * well-known catalog resources that no example expects and no waiting draft is about; each draft
 * that passes {@link DraftRules} waits in the review queue as EXAMPLE_CANDIDATE. Nothing reaches
 * the home page before a person accepts it and the quality check passes it.
 */
@Service
public class DraftHomeExamples {

  private final HomeExamples examples;
  private final DraftSources sources;
  private final ExampleDrafter drafter;
  private final ReviewQueue queue;
  private final AuditTrail audit;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;
  private final int sourcesPerRun;

  public DraftHomeExamples(
      HomeExamples examples,
      DraftSources sources,
      ExampleDrafter drafter,
      ReviewQueue queue,
      AuditTrail audit,
      ObjectMapper json,
      TransactionTemplate transaction,
      ExamplesProperties properties) {
    this.examples = examples;
    this.sources = sources;
    this.drafter = drafter;
    this.queue = queue;
    this.audit = audit;
    this.json = json;
    this.transaction = transaction;
    this.sourcesPerRun = properties.draftSources();
  }

  /** What a draft run gave: how many drafts wait in the review queue, and the model. */
  public record Drafted(int count, String model) {}

  /**
   * Throws {@link DraftsUnavailable} (audited) when there is no model, budget or answer. The model
   * call runs outside any transaction: no database connection waits for it. The drafts and the
   * audit entry are written together afterwards.
   */
  public Drafted draft(String adminId) {
    List<HomeExample> pool = examples.all(null);
    List<ReviewQueue.Item> waiting = queue.openItems(ReviewQueue.Kind.EXAMPLE_CANDIDATE);
    List<DraftSource> given = sources.wellKnown(sourcesPerRun, covered(pool, waiting));
    List<ExampleDraft> drafts;
    try {
      drafts =
          DraftRules.INSTANCE.keep(
              drafter.draft(given), given, taken(pool, waiting), drafter.getModel());
    } catch (DraftsUnavailable e) {
      audit.record(adminId, AuditTrail.Action.DRAFT_HOME_EXAMPLES, null, e.getReason().name());
      throw e;
    }
    transaction.executeWithoutResult(tx -> {
      drafts.forEach(this::open);
      audit.record(
          adminId, AuditTrail.Action.DRAFT_HOME_EXAMPLES, null, "DRAFTED " + drafts.size());
    });
    return new Drafted(drafts.size(), drafter.getModel());
  }

  /**
   * Resources an example expects, or a draft waiting for review is written for: not given again.
   */
  private static Set<UUID> covered(List<HomeExample> pool, List<ReviewQueue.Item> waiting) {
    return Stream.concat(
            pool.stream().flatMap(e -> e.getExpectedResourceIds().stream()),
            waiting.stream().map(ReviewQueue.Item::resourceId).filter(Objects::nonNull))
        .collect(Collectors.toSet());
  }

  /** Labels and queries in use: the pool's, and those of the drafts waiting for review. */
  private List<TakenText> taken(List<HomeExample> pool, List<ReviewQueue.Item> waiting) {
    return Stream.concat(
            pool.stream().map(e -> new TakenText(e.getLabel(), e.getQuery())),
            waiting.stream().flatMap(i -> textOf(i).stream()))
        .toList();
  }

  /** The texts of a draft this use case wrote; an unreadable one takes no text. */
  private Optional<TakenText> textOf(ReviewQueue.Item item) {
    try {
      JsonNode d = json.readTree(item.details() == null ? "" : item.details());
      JsonNode label = d.get("label");
      JsonNode query = d.get("query");
      if (label == null || query == null || !label.isString() || !query.isString()) {
        return Optional.empty();
      }
      return Optional.of(new TakenText(label.asString(), query.asString()));
    } catch (JacksonException e) {
      return Optional.empty();
    }
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
