package ai.helper2.admin.web;

import ai.helper2.api.common.model.AiContentMarkerDto;
import ai.helper2.api.common.model.AudiencePathDto;
import ai.helper2.api.generated.model.DraftedExampleDto;
import ai.helper2.api.generated.model.ReviewItemDto;
import ai.helper2.api.generated.model.ReviewKindDto;
import ai.helper2.api.generated.model.ReviewStatusDto;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.examples.WaitingDraft;
import ai.helper2.examples.usecase.ReadExampleDraft;
import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.shared.UtcTimes;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
class ReviewItemMapper {
  static final List<String> DRAFT_FIELDS = List.of("draft.label", "draft.query");

  private final ReadExampleDraft drafts;
  private final LlmSettings llm;

  ReviewItemMapper(ReadExampleDraft drafts, LlmSettings llm) {
    this.drafts = drafts;
    this.llm = llm;
  }

  String provider() {
    return llm.provider();
  }

  ReviewItemDto dto(ReviewQueue.Item i) {
    Optional<WaitingDraft> draft = drafts.in(i);
    return new ReviewItemDto(
            i.id(),
            ReviewKindDto.fromValue(i.kind().name()),
            ReviewStatusDto.fromValue(i.status().name()),
            i.reason(),
            UtcTimes.odt(i.createdAt()))
        .resourceId(i.resourceId())
        .resourceTitle(i.resourceTitle())
        .details(i.details())
        .draft(draft.map(ReviewItemMapper::draft).orElse(null))
        .aiContent(marker(draft.orElse(null)))
        .resolvedAt(UtcTimes.odt(i.resolvedAt()))
        .resolvedBy(i.resolvedBy())
        .categories(i.categories());
  }

  private @Nullable AiContentMarkerDto marker(@Nullable WaitingDraft d) {
    if (d == null) {
      return null;
    }
    return new AiContentMarkerDto(
        true,
        llm.provider(),
        List.of(d.getModel()),
        DRAFT_FIELDS,
        ReviewController.AI_MARKING_POLICY);
  }

  private static DraftedExampleDto draft(WaitingDraft d) {
    return new DraftedExampleDto(
        d.getLabel(), d.getQuery(), AudiencePathDto.fromValue(d.getPath().name()), d.getModel());
  }
}
