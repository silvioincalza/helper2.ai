package ai.helper2.admin.web;

import ai.helper2.admin.usecase.GetReviewEvidence;
import ai.helper2.admin.usecase.ResolveReviewItems;
import ai.helper2.api.generated.ReviewApi;
import ai.helper2.api.generated.model.AiContentMarkerDto;
import ai.helper2.api.generated.model.NewResourceDto;
import ai.helper2.api.generated.model.RelationshipTargetChangeDto;
import ai.helper2.api.generated.model.ReviewBulkItemDto;
import ai.helper2.api.generated.model.ReviewBulkResolutionDto;
import ai.helper2.api.generated.model.ReviewBulkResultDto;
import ai.helper2.api.generated.model.ReviewEvidenceDto;
import ai.helper2.api.generated.model.ReviewFieldDto;
import ai.helper2.api.generated.model.ReviewQuoteDto;
import ai.helper2.api.generated.model.ReviewRelationshipDto;
import ai.helper2.api.generated.model.ReviewRunDto;
import ai.helper2.api.generated.model.ValueOriginDto;
import ai.helper2.api.generated.model.VerificationDto;
import ai.helper2.catalog.ReviewEvidence;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.usecase.ChangeRelationshipTarget;
import ai.helper2.platform.BadRequestException;
import ai.helper2.platform.NotFoundException;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.UtcTimes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The review queue's evidence view, bulk decisions and relationship targets (ADMIN only, admin
 * port; ADR 0045, 0064).
 */
@RestController
@RequestMapping("/api/v1")
class ReviewController implements ReviewApi {

  /** The page that explains how AI text is marked (ADR 0038). */
  static final String AI_MARKING_POLICY = "/mission";

  private final GetReviewEvidence evidence;
  private final ResolveReviewItems resolveMany;
  private final ChangeRelationshipTarget changeTarget;

  ReviewController(
      GetReviewEvidence evidence,
      ResolveReviewItems resolveMany,
      ChangeRelationshipTarget changeTarget) {
    this.evidence = evidence;
    this.resolveMany = resolveMany;
    this.changeTarget = changeTarget;
  }

  /** ADR 0064: exactly one of a catalog resource, a resource to add, or a new name. */
  @Override
  public ResponseEntity<ReviewEvidenceDto> changeRelationshipTarget(
      UUID id, RelationshipTargetChangeDto body) {
    changeTarget.change(id, target(body), adminId());
    return getReviewEvidence(id);
  }

  private static ChangeRelationshipTarget.Target target(RelationshipTargetChangeDto body) {
    int given =
        (body.getResourceId() == null ? 0 : 1)
            + (body.getNewResource() == null ? 0 : 1)
            + (body.getTitle() == null ? 0 : 1);
    if (given != 1) {
      throw new BadRequestException("Give exactly one of resourceId, newResource and title.");
    }
    if (body.getResourceId() != null) {
      return new ChangeRelationshipTarget.Target.Existing(body.getResourceId());
    }
    NewResourceDto r = body.getNewResource();
    if (r != null) {
      return new ChangeRelationshipTarget.Target.Added(
          r.getTitle(), r.getUrl(), ResourceType.valueOf(r.getType().getValue()));
    }
    return new ChangeRelationshipTarget.Target.Renamed(body.getTitle());
  }

  @Override
  public ResponseEntity<ReviewBulkResultDto> resolveReviewItems(ReviewBulkResolutionDto body) {
    ResolveReviewItems.Outcome outcome =
        resolveMany.resolve(
            List.copyOf(body.getIds()),
            ReviewQueue.Status.valueOf(body.getStatus().getValue()),
            adminId(),
            body.getNote());
    return ResponseEntity.ok(
        new ReviewBulkResultDto(
            outcome.batch(),
            outcome.items().stream()
                .map(i ->
                    new ReviewBulkItemDto(
                        i.id(), ReviewBulkItemDto.OutcomeEnum.fromValue(i.result().name())))
                .toList()));
  }

  /** The signed-in administrator's provider subject (GitHub: the numeric user id). */
  private static String adminId() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }

  @Override
  public ResponseEntity<ReviewEvidenceDto> getReviewEvidence(UUID id) {
    GetReviewEvidence.View view =
        evidence.get(id).orElseThrow(() -> new NotFoundException("Review item not found."));
    ReviewEvidence.Evidence e = view.evidence();
    return ResponseEntity.ok(
        new ReviewEvidenceDto(
                AdminController.dto(view.item()),
                e.validationErrors(),
                e.fields().stream().map(ReviewController::field).toList())
            .run(e.run() == null ? null : run(e.run()))
            .relationship(e.relationship() == null ? null : relationship(e.relationship()))
            .aiContent(marker(e)));
  }

  /**
   * EU AI Act Art. 50(2) (ADR 0038): the field values, the relationship and the validation errors
   * (they quote the refused output) are model output, so the response says which paths a model
   * wrote, and with which model. Null when none.
   */
  private static @Nullable AiContentMarkerDto marker(ReviewEvidence.Evidence e) {
    if (e.run() == null || !e.aiWritten()) {
      return null;
    }
    List<String> paths = new ArrayList<>();
    if (!e.validationErrors().isEmpty()) {
      paths.add("validationErrors[]");
    }
    if (!e.fields().isEmpty()) {
      paths.add("fields[].value");
    }
    if (e.relationship() != null) {
      paths.add("relationship.kind");
      // A target a person chose (ADR 0064) is theirs, not the model's.
      if (!e.relationship().targetCurated()) {
        paths.add("relationship.target");
      }
    }
    return new AiContentMarkerDto(
        true, e.run().provider(), List.of(e.run().model()), paths, AI_MARKING_POLICY);
  }

  private static ReviewRunDto run(ReviewEvidence.Run r) {
    return new ReviewRunDto(
            r.id(),
            r.provider(),
            r.model(),
            r.promptVersion(),
            ReviewRunDto.OutcomeEnum.fromValue(r.outcome()),
            UtcTimes.odt(r.startedAt()))
        .finishedAt(UtcTimes.odt(r.finishedAt()))
        .extraEvidence(r.extraEvidence());
  }

  private static ReviewFieldDto field(ReviewEvidence.Field f) {
    return new ReviewFieldDto(
        f.field(),
        f.value(),
        ValueOriginDto.fromValue(f.origin().name()),
        VerificationDto.fromValue(f.verification().name()),
        f.quotes().stream().map(ReviewController::quote).toList());
  }

  private static ReviewRelationshipDto relationship(ReviewEvidence.Relationship r) {
    return new ReviewRelationshipDto(
            ReviewRelationshipDto.KindEnum.fromValue(r.kind()),
            r.target(),
            VerificationDto.fromValue(r.verification().name()),
            ValueOriginDto.fromValue(r.origin().name()),
            r.quotes().stream().map(ReviewController::quote).toList())
        .targetId(r.targetId())
        .targetOrigin(r.targetCurated() ? ValueOriginDto.CURATED : null);
  }

  private static ReviewQuoteDto quote(ReviewEvidence.Quote q) {
    return new ReviewQuoteDto(q.sourceUrl(), UtcTimes.odt(q.retrievedAt()))
        .excerpt(q.excerpt())
        .sourceId(q.sourceId());
  }
}
