package ai.helper2.scout.internal;

import ai.helper2.catalog.enrichment.StoredEnrichments;
import ai.helper2.scout.usecase.StoredAnswer;
import ai.helper2.scout.usecase.StoredAnswers;
import ai.helper2.scout.usecase.StoredClaimAnswer;
import ai.helper2.scout.usecase.StoredFieldAnswer;
import ai.helper2.shared.ResourceType;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
class CatalogStoredAnswers implements StoredAnswers {

  static final String CLOSED_BY = "scout";

  static final String CLOSED_NOTE =
      "Its only issues were values the quote does not state: they need no review";

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
        typeOf(s.type()),
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

  private static ResourceType typeOf(String type) {
    try {
      return ResourceType.valueOf(type);
    } catch (IllegalArgumentException e) {
      return ResourceType.OTHER;
    }
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
  public int closeItemsOfUnstatedValues() {
    return stored.closeItemsOfUnstatedValues(CLOSED_BY, CLOSED_NOTE);
  }

  @Override
  public void requestRun(UUID resourceId) {
    stored.requestRun(resourceId);
  }

  @Override
  public boolean done(String name) {
    return stored.done(name);
  }

  @Override
  public void markDone(String name) {
    stored.markDone(name);
  }
}
