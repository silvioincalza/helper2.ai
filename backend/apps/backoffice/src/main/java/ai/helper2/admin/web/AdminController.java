package ai.helper2.admin.web;

import ai.helper2.admin.usecase.ResolveReview;
import ai.helper2.api.generated.AdminApi;
import ai.helper2.api.generated.model.EnrichmentRecheckDto;
import ai.helper2.api.generated.model.ReviewItemDto;
import ai.helper2.api.generated.model.ReviewItemPageDto;
import ai.helper2.api.generated.model.ReviewKindDto;
import ai.helper2.api.generated.model.ReviewResolutionDto;
import ai.helper2.api.generated.model.ReviewStatusDto;
import ai.helper2.api.generated.model.ScoutRunDto;
import ai.helper2.api.generated.model.ScoutRunRequestDto;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.usecase.BackfillCategories;
import ai.helper2.catalog.usecase.ListReviewItems;
import ai.helper2.catalog.usecase.ResolveReviewItem;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.NotFoundException;
import ai.helper2.platform.ProblemType;
import ai.helper2.scout.RunSummary;
import ai.helper2.scout.ScoutStore;
import ai.helper2.scout.usecase.RecheckEnrichments;
import ai.helper2.scout.usecase.RecheckResult;
import ai.helper2.scout.usecase.StartScoutRun;
import ai.helper2.scout.usecase.UnknownSource;
import ai.helper2.shared.UtcTimes;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class AdminController implements AdminApi {

  private final ListReviewItems items;
  private final ResolveReview resolve;
  private final StartScoutRun startRun;
  private final ScoutStore store;
  private final RecheckEnrichments recheck;
  private final BackfillCategories backfill;

  AdminController(
      ListReviewItems items,
      ResolveReview resolve,
      StartScoutRun startRun,
      ScoutStore store,
      RecheckEnrichments recheck,
      BackfillCategories backfill) {
    this.items = items;
    this.resolve = resolve;
    this.startRun = startRun;
    this.store = store;
    this.recheck = recheck;
    this.backfill = backfill;
  }

  /** Re-checks stored enrichment answers by code (ADR 0056); counts only. */
  @Override
  public ResponseEntity<EnrichmentRecheckDto> recheckEnrichments() {
    RecheckResult r = recheck.runByAdmin(adminId());
    return ResponseEntity.ok(
        new EnrichmentRecheckDto(
            r.getChecked(), r.getFieldsRejected(), r.getClaimsDropped(), r.getRunsRequested()));
  }

  @Override
  public ResponseEntity<ReviewItemPageDto> listReviewItems(
      @Nullable ReviewStatusDto status,
      @Nullable ReviewKindDto kind,
      @Nullable String category,
      Integer page,
      Integer size) {
    ReviewQueue.Page found =
        items.list(
            status == null ? null : ReviewQueue.Status.valueOf(status.getValue()),
            kind == null ? null : ReviewQueue.Kind.valueOf(kind.getValue()),
            category,
            page,
            size);
    return ResponseEntity.ok(
        new ReviewItemPageDto(
            found.items().stream().map(AdminController::dto).toList(),
            Math.toIntExact(found.total())));
  }

  @Override
  public ResponseEntity<ReviewItemDto> resolveReviewItem(UUID id, ReviewResolutionDto body) {
    return switch (resolve.resolve(
        id, ReviewQueue.Status.valueOf(body.getStatus().getValue()), adminId(), body.getNote())) {
      case ResolveReviewItem.Outcome.Resolved r -> ResponseEntity.ok(dto(r.item()));
      case ResolveReviewItem.Outcome.NotOpen n ->
          throw new ApiProblem(
              ProblemType.ALREADY_DECIDED,
              "This item was already decided: " + n.item().status() + ".");
      case ResolveReviewItem.Outcome.NotFound f ->
          throw new NotFoundException("Review item not found.");
    };
  }

  @Override
  public ResponseEntity<List<ScoutRunDto>> listScoutRuns() {
    return ResponseEntity.ok(store.recent(50).stream().map(AdminController::dto).toList());
  }

  @Override
  public ResponseEntity<List<ScoutRunDto>> startScoutRun(@Nullable ScoutRunRequestDto body) {
    List<RunSummary> runs;
    try {
      runs = startRun.start(adminId(), body == null ? null : body.getSourceId());
    } catch (UnknownSource e) {
      throw new NotFoundException("No collector has this source id.");
    }
    if (runs == null) {
      throw new ApiProblem(
          ProblemType.RUN_IN_PROGRESS,
          "A Scout run is already in progress. Try again when it ends.");
    }
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(runs.stream().map(AdminController::dto).toList());
  }

  @Override
  public ResponseEntity<Void> backfillCategories() {
    backfill.start(adminId());
    return ResponseEntity.status(HttpStatus.ACCEPTED).build();
  }

  /** The signed-in administrator's provider subject (GitHub: the numeric user id). */
  private static String adminId() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }

  /** One review item as the contract shows it; the evidence view shows the item the same way. */
  static ReviewItemDto dto(ReviewQueue.Item i) {
    return new ReviewItemDto(
            i.id(),
            ReviewKindDto.fromValue(i.kind().name()),
            ReviewStatusDto.fromValue(i.status().name()),
            i.reason(),
            UtcTimes.odt(i.createdAt()))
        .resourceId(i.resourceId())
        .resourceTitle(i.resourceTitle())
        .details(i.details())
        .resolvedAt(UtcTimes.odt(i.resolvedAt()))
        .resolvedBy(i.resolvedBy())
        .categories(i.categories());
  }

  /** One Scout run as the contract shows it; the overview lists runs the same way. */
  static ScoutRunDto dto(RunSummary r) {
    return new ScoutRunDto(
            r.getId(),
            r.getSourceId(),
            ScoutRunDto.StatusEnum.fromValue(r.getStatus().name()),
            UtcTimes.odt(r.getStartedAt()),
            r.getItemsSeen(),
            r.getItemsChanged())
        .finishedAt(UtcTimes.odt(r.getFinishedAt()))
        .enrichmentsQueued(r.getEnrichmentsQueued())
        .message(r.getMessage());
  }
}
