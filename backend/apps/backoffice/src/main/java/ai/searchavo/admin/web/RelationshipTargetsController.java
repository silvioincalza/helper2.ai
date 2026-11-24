package ai.searchavo.admin.web;

import ai.searchavo.admin.usecase.DecideRelationshipTargetGroup;
import ai.searchavo.admin.usecase.DecideRelationshipTargetGroup.Choice;
import ai.searchavo.admin.usecase.ListRelationshipTargetGroups;
import ai.searchavo.api.generated.RelationshipTargetsApi;
import ai.searchavo.api.generated.model.RelationshipTargetGroupDecisionDto;
import ai.searchavo.api.generated.model.RelationshipTargetGroupDto;
import ai.searchavo.api.generated.model.RelationshipTargetGroupOutcomeDto;
import ai.searchavo.api.generated.model.RelationshipTargetGroupPageDto;
import ai.searchavo.catalog.KnownTarget;
import ai.searchavo.catalog.RelationshipTargets;
import ai.searchavo.platform.ApiProblem;
import ai.searchavo.platform.BadRequestException;
import ai.searchavo.platform.NotFoundException;
import ai.searchavo.platform.ProblemType;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class RelationshipTargetsController implements RelationshipTargetsApi {

  private final ListRelationshipTargetGroups groups;
  private final DecideRelationshipTargetGroup decide;

  RelationshipTargetsController(
      ListRelationshipTargetGroups groups, DecideRelationshipTargetGroup decide) {
    this.groups = groups;
    this.decide = decide;
  }

  @Override
  public ResponseEntity<RelationshipTargetGroupPageDto> listRelationshipTargetGroups(
      Integer page, Integer size) {
    ListRelationshipTargetGroups.Page read = groups.list(page, size);
    return ResponseEntity.ok(
        new RelationshipTargetGroupPageDto(
            read.groups().stream().map(RelationshipTargetsController::dto).toList(),
            Math.toIntExact(read.total())));
  }

  @Override
  public ResponseEntity<RelationshipTargetGroupOutcomeDto> decideRelationshipTargetGroup(
      RelationshipTargetGroupDecisionDto body) {
    return switch (decide.decide(body.getKey(), choice(body), Admins.id(), body.getNote())) {
      case DecideRelationshipTargetGroup.Outcome.Decided d ->
          ResponseEntity.ok(
              new RelationshipTargetGroupOutcomeDto(d.batch(), d.decided(), d.skipped(), d.left()));
      case DecideRelationshipTargetGroup.Outcome.NoOpenItems none ->
          throw new NotFoundException("No open relationship candidate has this target.");
      case DecideRelationshipTargetGroup.Outcome.UnknownResource unknown ->
          throw new BadRequestException("No resource has this id.");
      case DecideRelationshipTargetGroup.Outcome.InvalidKnownTargetName name ->
          throw new ApiProblem(ProblemType.INVALID_REQUEST, "This name cannot be a known target.");
      case DecideRelationshipTargetGroup.Outcome.KnownTargetListFull full ->
          throw KnownTargetProblems.listIsFull(full.limit());
    };
  }

  private static Choice choice(RelationshipTargetGroupDecisionDto body) {
    UUID resource = body.getResourceId();
    var decision = body.getDecision();
    if ((decision == RelationshipTargetGroupDecisionDto.DecisionEnum.LINK) != (resource != null)) {
      throw new BadRequestException("resourceId is required for LINK and not allowed otherwise.");
    }
    return switch (decision) {
      case LINK -> new Choice.Link(resource);
      case VENDOR -> new Choice.Known(KnownTarget.Kind.VENDOR);
      case SERVICE -> new Choice.Known(KnownTarget.Kind.SERVICE);
      case TOOL -> new Choice.Known(KnownTarget.Kind.TOOL);
      case REJECT -> new Choice.Reject();
    };
  }

  private static RelationshipTargetGroupDto dto(RelationshipTargets.Group g) {
    return new RelationshipTargetGroupDto(
        g.key(),
        g.name(),
        g.items(),
        g.kinds().stream().map(RelationshipTargetGroupDto.KindsEnum::fromValue).toList(),
        g.examples());
  }
}
