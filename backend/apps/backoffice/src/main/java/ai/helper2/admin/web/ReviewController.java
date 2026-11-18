package ai.helper2.admin.web;

import ai.helper2.admin.usecase.DecideReviewItem;
import ai.helper2.admin.usecase.GetReviewEvidence;
import ai.helper2.admin.usecase.ListReviewQueue;
import ai.helper2.admin.usecase.ResolveReviewItems;
import ai.helper2.admin.usecase.ReviewItemView;
import ai.helper2.admin.usecase.ShowReviewItem;
import ai.helper2.api.AiContentMarking;
import ai.helper2.api.common.model.AiContentMarkerDto;
import ai.helper2.api.common.model.ValueOriginDto;
import ai.helper2.api.common.model.VerificationDto;
import ai.helper2.api.generated.ReviewApi;
import ai.helper2.api.generated.model.AlreadyListedDto;
import ai.helper2.api.generated.model.CandidateEditDto;
import ai.helper2.api.generated.model.DraftedExampleDto;
import ai.helper2.api.generated.model.KnownTargetWriteDto;
import ai.helper2.api.generated.model.NewResourceDto;
import ai.helper2.api.generated.model.RelationshipTargetChangeDto;
import ai.helper2.api.generated.model.ResourceFieldDto;
import ai.helper2.api.generated.model.ResourceFieldValueDto;
import ai.helper2.api.generated.model.ReviewBulkItemDto;
import ai.helper2.api.generated.model.ReviewBulkResolutionDto;
import ai.helper2.api.generated.model.ReviewBulkResultDto;
import ai.helper2.api.generated.model.ReviewEvidenceDto;
import ai.helper2.api.generated.model.ReviewFieldDto;
import ai.helper2.api.generated.model.ReviewItemDto;
import ai.helper2.api.generated.model.ReviewItemPageDto;
import ai.helper2.api.generated.model.ReviewKindDto;
import ai.helper2.api.generated.model.ReviewQuoteDto;
import ai.helper2.api.generated.model.ReviewRelationshipDto;
import ai.helper2.api.generated.model.ReviewResolutionDto;
import ai.helper2.api.generated.model.ReviewRunDto;
import ai.helper2.api.generated.model.ReviewStatusDto;
import ai.helper2.catalog.KnownTarget;
import ai.helper2.catalog.ReviewEvidence;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.usecase.ChangeRelationshipTarget;
import ai.helper2.catalog.usecase.CurateField;
import ai.helper2.catalog.usecase.EditKnownTargetCandidate;
import ai.helper2.catalog.usecase.ManageKnownTargets;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.BadRequestException;
import ai.helper2.platform.NotFoundException;
import ai.helper2.platform.ProblemType;
import ai.helper2.research.ResearchFilter;
import ai.helper2.scout.usecase.CandidateEdit;
import ai.helper2.scout.usecase.EditCandidate;
import ai.helper2.scout.usecase.ListedResource;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.UtcTimes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class ReviewController implements ReviewApi {
  static final String AI_MARKING_POLICY = AiContentMarking.POLICY;

  private final GetReviewEvidence evidence;
  private final ResolveReviewItems resolveMany;
  private final ChangeRelationshipTarget changeTarget;
  private final EditCandidate editCandidate;
  private final ShowReviewItem item;
  private final CurateField curateField;
  private final ReviewItemMapper reviewItems;
  private final EditKnownTargetCandidate editProposal;
  private final ListReviewQueue items;
  private final DecideReviewItem decide;

  ReviewController(
      GetReviewEvidence evidence,
      ResolveReviewItems resolveMany,
      ChangeRelationshipTarget changeTarget,
      EditCandidate editCandidate,
      ShowReviewItem item,
      CurateField curateField,
      ReviewItemMapper reviewItems,
      EditKnownTargetCandidate editProposal,
      ListReviewQueue items,
      DecideReviewItem decide) {
    this.items = items;
    this.decide = decide;
    this.editProposal = editProposal;
    this.evidence = evidence;
    this.resolveMany = resolveMany;
    this.changeTarget = changeTarget;
    this.editCandidate = editCandidate;
    this.item = item;
    this.curateField = curateField;
    this.reviewItems = reviewItems;
  }

  @Override
  public ResponseEntity<ReviewItemPageDto> listReviewItems(
      @Nullable ReviewStatusDto status,
      @Nullable ReviewKindDto kind,
      @Nullable String category,
      @Nullable String research,
      Integer page,
      Integer size) {
    ListReviewQueue.Page found =
        items.list(
            status == null ? null : ReviewItemView.Status.valueOf(status.getValue()),
            kind == null ? null : ReviewQueue.Kind.valueOf(kind.getValue()),
            category,
            filter(research),
            page,
            size);
    return ResponseEntity.ok(
        new ReviewItemPageDto(
            found.items().stream().map(reviewItems::dto).toList(), Math.toIntExact(found.total())));
  }

  private static @Nullable ResearchFilter filter(@Nullable String research) {
    if (research == null) {
      return null;
    }
    try {
      return ResearchFilter.valueOf(research);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("research must be KEEP, REJECT, DUPLICATE, ACTIVE or NONE");
    }
  }

  @Override
  public ResponseEntity<ReviewItemDto> resolveReviewItem(UUID id, ReviewResolutionDto body) {
    DecideReviewItem.Decided decided =
        decide.decide(
            id,
            ReviewItemView.Status.valueOf(body.getStatus().getValue()),
            Admins.id(),
            body.getNote());
    return switch (decided) {
      case DecideReviewItem.Decided.Resolved r ->
          ResponseEntity.ok(
              reviewItems.dto(r.item()).alreadyListed(alreadyListed(r.alreadyListed())));
      case DecideReviewItem.Decided.NotOpen n ->
          throw new ApiProblem(
              ProblemType.ALREADY_DECIDED, "This item was already decided: " + n.status() + ".");
      case DecideReviewItem.Decided.NotFound f ->
          throw new NotFoundException("Review item not found.");
      case DecideReviewItem.Decided.NotAdded n -> throw ReviewProblems.notAdded(n.why());
    };
  }

  private static @Nullable AlreadyListedDto alreadyListed(@Nullable ListedResource listed) {
    return listed == null ? null : new AlreadyListedDto(listed.getId(), listed.getTitle());
  }

  @Override
  public ResponseEntity<Void> setResourceField(
      UUID id, ResourceFieldDto field, ResourceFieldValueDto body) {
    return switch (curateField.set(id, field.getValue(), body.getValue(), Admins.id())) {
      case CurateField.Outcome.Curated curated -> ResponseEntity.noContent().build();
      case CurateField.Outcome.UnknownField unknown ->
          throw new BadRequestException("This field cannot be set: " + unknown.field() + ".");
      case CurateField.Outcome.ValueNotAllowed value ->
          throw new BadRequestException("This value is not allowed for " + value.field() + ".");
      case CurateField.Outcome.InvalidDescription description ->
          throw new BadRequestException(
              "The description must be 1 to " + description.maxLength() + " characters.");
      case CurateField.Outcome.NotADate notADate ->
          throw new BadRequestException("The published date must be YYYY-MM-DD.");
      case CurateField.Outcome.DateInTheFuture future ->
          throw new BadRequestException("The published date is in the future.");
      case CurateField.Outcome.NoSuchResource missing -> throw resourceNotFound();
    };
  }

  @Override
  public ResponseEntity<Void> dropResourceField(UUID id, ResourceFieldDto field) {
    return switch (curateField.drop(id, field.getValue(), Admins.id())) {
      case CurateField.DropOutcome.Dropped dropped -> ResponseEntity.noContent().build();
      case CurateField.DropOutcome.NotWrittenByAi notByAi ->
          throw new BadRequestException("The AI writes no such field.");
      case CurateField.DropOutcome.NoSuchResource missing -> throw resourceNotFound();
    };
  }

  private static NotFoundException resourceNotFound() {
    return new NotFoundException("Resource not found.");
  }

  @Override
  public ResponseEntity<ReviewItemDto> editCandidate(UUID id, CandidateEditDto body) {
    CandidateEdit change =
        new CandidateEdit(
            body.getTitle(),
            body.getType() == null ? null : ResourceType.valueOf(body.getType().getValue()),
            body.getTags() == null ? null : Set.copyOf(body.getTags()),
            body.getCanonicalUrl(),
            body.getPricing(),
            body.getOpenSource(),
            body.getLicense(),
            body.getAudience(),
            body.getTechLevel(),
            body.getDescription(),
            body.getDescriptionWrittenByAi(),
            body.getLanguage(),
            body.getPublishedAt());
    switch (editCandidate.edit(id, body.getExpectedRevision(), change, Admins.id())) {
      case EditCandidate.Outcome.Edited e -> {}
      case EditCandidate.Outcome.NotFound n ->
          throw new NotFoundException("Review item not found.");
      case EditCandidate.Outcome.NotOpen n ->
          throw new ApiProblem(ProblemType.ALREADY_DECIDED, "The item is no longer open.");
      case EditCandidate.Outcome.NotACandidate n ->
          throw new ApiProblem(ProblemType.NOT_APPLICABLE, "The item is not a resource candidate.");
      case EditCandidate.Outcome.ChangedMeanwhile c ->
          throw new ApiProblem(
              ProblemType.VERSION_CONFLICT,
              "The candidate changed since you opened it. Reload it, then edit again.");
      case EditCandidate.Outcome.Invalid i -> throw new BadRequestException(i.getReason());
    }
    return ResponseEntity.ok(
        reviewItems.dto(
            item.get(id).orElseThrow(() -> new NotFoundException("Review item not found."))));
  }

  @Override
  public ResponseEntity<ReviewItemDto> editKnownTargetCandidate(UUID id, KnownTargetWriteDto body) {
    switch (editProposal.edit(
        id,
        KnownTarget.Kind.valueOf(body.getKind().getValue()),
        body.getName(),
        body.getOtherNames(),
        Admins.id())) {
      case EDITED -> {}
      case NOT_FOUND -> throw new NotFoundException("Review item not found.");
      case NOT_OPEN ->
          throw new ApiProblem(ProblemType.ALREADY_DECIDED, "The item is no longer open.");
      case NOT_A_PROPOSAL ->
          throw new ApiProblem(
              ProblemType.NOT_APPLICABLE, "The item is not a known target proposal.");
      case INVALID ->
          throw new BadRequestException(
              "A name is "
                  + ManageKnownTargets.MIN_NAME
                  + " to "
                  + ManageKnownTargets.MAX_NAME
                  + " characters on one plain line, with at most "
                  + ManageKnownTargets.MAX_OTHER_NAMES
                  + " other names.");
    }
    return ResponseEntity.ok(
        reviewItems.dto(
            item.get(id).orElseThrow(() -> new NotFoundException("Review item not found."))));
  }

  @Override
  public ResponseEntity<ReviewEvidenceDto> changeRelationshipTarget(
      UUID id, RelationshipTargetChangeDto body) {
    return switch (changeTarget.change(id, target(body), Admins.id())) {
      case ChangeRelationshipTarget.Outcome.Changed changed -> getReviewEvidence(id);
      case ChangeRelationshipTarget.Outcome.InvalidName name ->
          throw new BadRequestException(
              "The name must be 1 to " + name.maxLength() + " characters.");
      case ChangeRelationshipTarget.Outcome.InvalidResourceTitle title ->
          throw new BadRequestException(
              "The name must be 1 to " + title.maxLength() + " characters.");
      case ChangeRelationshipTarget.Outcome.InvalidResourceUrl url ->
          throw new BadRequestException("The address must be the resource's own https address.");
      case ChangeRelationshipTarget.Outcome.InvalidResourceType type ->
          throw new BadRequestException("A relationship can point only at a tool or a model.");
      case ChangeRelationshipTarget.Outcome.NotFound notFound ->
          throw new NotFoundException("Review item not found.");
      case ChangeRelationshipTarget.Outcome.NotOpen notOpen ->
          throw new ApiProblem(ProblemType.ALREADY_DECIDED, "The item is no longer open.");
      case ChangeRelationshipTarget.Outcome.NotARelationship other ->
          throw new ApiProblem(
              ProblemType.NOT_APPLICABLE, "The item is not a relationship candidate.");
      case ChangeRelationshipTarget.Outcome.TitleTaken taken ->
          throw new ApiProblem(
              ProblemType.DUPLICATE, "The resource already has a relationship with this target.");
      case ChangeRelationshipTarget.Outcome.UnknownTarget unknown ->
          throw new BadRequestException("No resource has this id.");
      case ChangeRelationshipTarget.Outcome.SelfLink self ->
          throw new BadRequestException("A resource cannot point at itself.");
    };
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
    return switch (resolveMany.resolve(
        List.copyOf(body.getIds()),
        ReviewItemView.Status.valueOf(body.getStatus().getValue()),
        Admins.id(),
        body.getNote())) {
      case ResolveReviewItems.Outcome.Decided decided -> ResponseEntity.ok(bulkResult(decided));
      case ResolveReviewItems.Outcome.RepeatedId repeated ->
          throw new BadRequestException("ids must be unique");
      case ResolveReviewItems.Outcome.NotAdded n -> throw ReviewProblems.notAdded(n.why());
    };
  }

  private static ReviewBulkResultDto bulkResult(ResolveReviewItems.Outcome.Decided decided) {
    return new ReviewBulkResultDto(
        decided.batch(),
        decided.items().stream()
            .map(i ->
                new ReviewBulkItemDto(
                    i.id(), ReviewBulkItemDto.OutcomeEnum.fromValue(i.result().name())))
            .toList());
  }

  @Override
  public ResponseEntity<ReviewEvidenceDto> getReviewEvidence(UUID id) {
    GetReviewEvidence.View view =
        evidence.get(id).orElseThrow(() -> new NotFoundException("Review item not found."));
    ReviewItemDto item = reviewItems.dto(view.item());
    return ResponseEntity.ok(
        new ReviewEvidenceDto(
                item,
                view.validationErrors(),
                view.fields().stream().map(ReviewController::field).toList())
            .run(view.run() == null ? null : run(view.run()))
            .relationship(view.relationship() == null ? null : relationship(view.relationship()))
            .aiContent(marker(view, item)));
  }

  private @Nullable AiContentMarkerDto marker(GetReviewEvidence.View e, ReviewItemDto item) {
    ReviewEvidence.Run run = e.aiWritten() ? e.run() : null;
    DraftedExampleDto draft = item.getDraft();
    if (run == null && draft == null) {
      return null;
    }
    List<String> paths = new ArrayList<>();
    List<String> models = new ArrayList<>();
    if (run != null) {
      paths.addAll(runPaths(e));
      models.add(run.model());
    }
    if (draft != null) {
      ReviewItemMapper.DRAFT_FIELDS.forEach(f -> paths.add("item." + f));
      if (!models.contains(draft.getModel())) {
        models.add(draft.getModel());
      }
    }
    String provider = run != null ? run.provider() : reviewItems.provider();
    return new AiContentMarkerDto(true, provider, models, paths, AI_MARKING_POLICY);
  }

  private static List<String> runPaths(GetReviewEvidence.View e) {
    List<String> paths = new ArrayList<>();
    if (!e.validationErrors().isEmpty()) {
      paths.add("validationErrors[]");
    }
    if (!e.fields().isEmpty()) {
      paths.add("fields[].value");
    }
    if (e.relationship() != null) {
      paths.add("relationship.kind");
      if (!e.relationship().targetCurated()) {
        paths.add("relationship.target");
      }
    }
    return paths;
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
