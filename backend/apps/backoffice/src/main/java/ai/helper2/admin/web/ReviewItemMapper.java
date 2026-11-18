package ai.helper2.admin.web;

import ai.helper2.admin.usecase.ReviewDetails;
import ai.helper2.admin.usecase.ReviewItemView;
import ai.helper2.api.common.model.AiContentMarkerDto;
import ai.helper2.api.common.model.AudiencePathDto;
import ai.helper2.api.generated.model.DraftedExampleDto;
import ai.helper2.api.generated.model.KnownTargetKindDto;
import ai.helper2.api.generated.model.ReviewCandidateDetailsDto;
import ai.helper2.api.generated.model.ReviewDetailsDto;
import ai.helper2.api.generated.model.ReviewDetailsShapeDto;
import ai.helper2.api.generated.model.ReviewItemDto;
import ai.helper2.api.generated.model.ReviewKindDto;
import ai.helper2.api.generated.model.ReviewKnownTargetProposalDetailsDto;
import ai.helper2.api.generated.model.ReviewStatusDto;
import ai.helper2.api.generated.model.ReviewTextDetailsDto;
import ai.helper2.examples.WaitingDraft;
import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.shared.UtcTimes;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
class ReviewItemMapper {
  static final List<String> DRAFT_FIELDS = List.of("draft.label", "draft.query");

  private final LlmSettings llm;

  ReviewItemMapper(LlmSettings llm) {
    this.llm = llm;
  }

  String provider() {
    return llm.provider();
  }

  ReviewItemDto dto(ReviewItemView i) {
    return new ReviewItemDto(
            i.id(),
            ReviewKindDto.fromValue(i.kind().name()),
            ReviewStatusDto.fromValue(i.status().name()),
            i.reason(),
            UtcTimes.odt(i.createdAt()))
        .resourceId(i.resourceId())
        .resourceTitle(i.resourceTitle())
        .details(details(i.details()))
        .draft(i.draft() == null ? null : draft(i.draft()))
        .aiContent(marker(i.draft()))
        .resolvedAt(UtcTimes.odt(i.resolvedAt()))
        .resolvedBy(i.resolvedBy())
        .categories(i.categories());
  }

  private static @Nullable ReviewDetailsDto details(@Nullable ReviewDetails details) {
    return switch (details) {
      case null -> null;
      case ReviewDetails.Text t -> new ReviewTextDetailsDto(ReviewDetailsShapeDto.TEXT, t.text());
      case ReviewDetails.Candidate c ->
          new ReviewCandidateDetailsDto(
                  ReviewDetailsShapeDto.CANDIDATE,
                  c.revision(),
                  c.url(),
                  c.title(),
                  c.type(),
                  c.tags(),
                  c.foundBy(),
                  c.edited())
              .snippet(c.snippet());
      case ReviewDetails.KnownTargetProposal p ->
          new ReviewKnownTargetProposalDetailsDto(
              ReviewDetailsShapeDto.KNOWN_TARGET_PROPOSAL,
              p.name(),
              KnownTargetKindDto.fromValue(p.kind().name()),
              ReviewKnownTargetProposalDetailsDto.RuleEnum.fromValue(p.rule()),
              p.count(),
              p.examples(),
              p.otherNames(),
              p.edited());
    };
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
