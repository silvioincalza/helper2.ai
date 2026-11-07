package ai.helper2.catalog.enrichment;

import ai.helper2.shared.ValueOrigin;
import ai.helper2.shared.Verification;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Inbound port of the catalog for re-checking stored enrichment answers by code (ADR 0056): what
 * earlier runs left, and the few changes a re-check may make. No model is involved.
 */
public interface StoredEnrichments {

  /** An AI-written field with the excerpts that support it. */
  record StoredField(
      UUID id,
      String field,
      String value,
      ValueOrigin origin,
      Verification verification,
      List<String> excerpts) {}

  /** A summary claim of the shown summary, with its excerpts. */
  record StoredClaim(UUID id, String text, List<String> excerpts) {}

  /**
   * What enrichment left on one resource; [promptVersion] is the prompt of its latest judged run.
   */
  record Stored(
      UUID resourceId,
      String title,
      String type,
      @Nullable String promptVersion,
      List<StoredField> fields,
      List<StoredClaim> claims) {}

  /**
   * Resources an enrichment run judged (VALIDATED, PARTIAL or REJECTED), by id after [after] (null:
   * from the start), at most [limit].
   */
  List<UUID> judgedAfter(@Nullable UUID after, int limit);

  /** The resource's AI-written fields and the claims of its latest summary. */
  Stored stored(UUID resourceId);

  /** Sets the fields to REJECTED: they show as "Not stated". Returns how many changed. */
  int rejectFields(List<UUID> fieldIds);

  /** Deletes the claims, and the evidence only they used. Returns how many went. */
  int dropClaims(List<UUID> claimIds);

  /**
   * Puts the resource back to NOT_RUN and records when: the enrichment backlog takes it again, and
   * the runs judged before the request no longer skip it as UNCHANGED.
   */
  void requestRun(UUID resourceId);

  /** True the first time [name] is claimed, cluster-wide; false ever after. */
  boolean claimOnce(String name);
}
