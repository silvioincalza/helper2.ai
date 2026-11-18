package ai.helper2.scout.internal;

import ai.helper2.catalog.JobMarkers;
import ai.helper2.catalog.enrichment.StoredEnrichments;
import ai.helper2.scout.usecase.StoredAnswer;
import ai.helper2.scout.usecase.StoredAnswers;
import ai.helper2.scout.usecase.StoredClaimAnswer;
import ai.helper2.scout.usecase.StoredFieldAnswer;
import ai.helper2.shared.ResourceType;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.EnumUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
class CatalogStoredAnswers implements StoredAnswers {

  static final String CLOSED_BY = "scout";

  static final String CLOSED_NOTE =
      "Nothing to decide: a partial run already dropped what failed, or its only issues were"
          + " values the quote does not state";

  private final StoredEnrichments stored;
  private final JobMarkers markers;

  CatalogStoredAnswers(StoredEnrichments stored, JobMarkers markers) {
    this.stored = stored;
    this.markers = markers;
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
    return EnumUtils.getEnum(ResourceType.class, type, ResourceType.OTHER);
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
  public int closeItemsWithNothingToDecide() {
    return stored.closeItemsWithNothingToDecide(CLOSED_BY, CLOSED_NOTE);
  }

  @Override
  public void requestRun(UUID resourceId) {
    stored.requestRun(resourceId);
  }

  @Override
  public boolean done(String name) {
    return markers.marker(name).isPresent();
  }

  @Override
  public void markDone(String name) {
    markers.mark(name, "done");
  }
}
