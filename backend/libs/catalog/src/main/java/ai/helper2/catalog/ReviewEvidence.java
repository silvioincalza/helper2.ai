package ai.helper2.catalog;

import ai.helper2.shared.ValueOrigin;
import ai.helper2.shared.Verification;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Query port of the catalog: what a review item is about, read through the item's link to its
 * enrichment run or relationship (ADR 0045). Never read from the item's details.
 */
public interface ReviewEvidence {

  /** Quotes shown per field or relationship. */
  int MAX_QUOTES = 5;

  /** Fields shown per run, and validation errors per run. */
  int MAX_FIELDS = 50;

  /**
   * The evidence of review item [itemId]; {@link Evidence#NONE} when the item links to no run and
   * no relationship, or does not exist.
   */
  Evidence of(UUID itemId);

  /**
   * The names of the fields that the run of review item [itemId] left CONFLICTING, sorted, with no
   * limit; empty when the item has no run or does not exist. For a decision rule: it does not
   * depend on what {@link #of} shows.
   */
  List<String> conflictingFields(UUID itemId);

  /**
   * [run] is null for an item with no run. [validationErrors] and [fields] are empty for a
   * relationship candidate; [relationship] is null for every other kind.
   */
  record Evidence(
      @Nullable Run run,
      List<String> validationErrors,
      List<Field> fields,
      @Nullable Relationship relationship) {

    public static final Evidence NONE = new Evidence(null, List.of(), List.of(), null);

    /**
     * True when a model wrote any of it: a field value, a relationship, or a validation error (it
     * quotes the refused model output).
     */
    public boolean aiWritten() {
      return !fields.isEmpty() || relationship != null || !validationErrors.isEmpty();
    }
  }

  /** One enrichment run; [extraEvidence] names the README or model card of its gap round. */
  record Run(
      UUID id,
      String provider,
      String model,
      String promptVersion,
      String outcome,
      Instant startedAt,
      @Nullable Instant finishedAt,
      @Nullable String extraEvidence) {}

  /** A field the run stored, with at most {@link #MAX_QUOTES} quotes. */
  record Field(
      String field,
      String value,
      ValueOrigin origin,
      Verification verification,
      List<Quote> quotes) {}

  /**
   * A relationship a run proposed, with at most {@link #MAX_QUOTES} quotes. [origin] is the kind's;
   * [targetCurated] is true when an admin chose the target (ADR 0064).
   */
  record Relationship(
      String kind,
      String target,
      @Nullable UUID targetId,
      Verification verification,
      ValueOrigin origin,
      boolean targetCurated,
      List<Quote> quotes) {}

  /** A quote copied from a source; [sourceId] is null for a page the run read itself. */
  record Quote(
      @Nullable String excerpt, String sourceUrl, @Nullable String sourceId, Instant retrievedAt) {}
}
