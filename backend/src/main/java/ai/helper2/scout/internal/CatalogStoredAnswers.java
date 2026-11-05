package ai.helper2.scout.internal;

import ai.helper2.catalog.enrichment.StoredEnrichments;
import ai.helper2.scout.usecase.StoredAnswer;
import ai.helper2.scout.usecase.StoredAnswers;
import ai.helper2.scout.usecase.StoredClaimAnswer;
import ai.helper2.scout.usecase.StoredFieldAnswer;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The Scout's view of stored enrichment answers for a re-check (ADR 0056); the catalog owns the
 * SQL.
 */
@Component
class CatalogStoredAnswers implements StoredAnswers {

  private final StoredEnrichments stored;

  CatalogStoredAnswers(StoredEnrichments stored) {
    this.stored = stored;
  }

  @Override
  public List<UUID> judgedAfter(@Nullable UUID after, int limit) {
    return stored.judgedAfter(after, limit);
  }

  @Override
  public StoredAnswer stored(UUID resourceId) {
    StoredEnrichments.Stored s = stored.stored(resourceId);
    return new StoredAnswer(
        s.resourceId(),
        s.title(),
        s.promptVersion(),
        s.fields().stream()
            .map(f ->
                new StoredFieldAnswer(
                    f.id(), f.field(), f.value(), f.origin(), f.verification(), f.excerpts()))
            .toList(),
        s.claims().stream()
            .map(c -> new StoredClaimAnswer(c.id(), c.text(), c.excerpts()))
            .toList());
  }

  @Override
  public int rejectFields(List<UUID> ids) {
    return stored.rejectFields(ids);
  }

  @Override
  public int dropClaims(List<UUID> ids) {
    return stored.dropClaims(ids);
  }

  @Override
  public void requestRun(UUID resourceId) {
    stored.requestRun(resourceId);
  }

  @Override
  public boolean claimOnce(String name) {
    return stored.claimOnce(name);
  }
}
