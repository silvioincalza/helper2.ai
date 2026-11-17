package ai.helper2.admin.web;

import ai.helper2.admin.usecase.DecideRelationshipTargetGroup;
import ai.helper2.admin.usecase.DecideRelationshipTargetGroup.Choice;
import ai.helper2.api.generated.RelationshipTargetsApi;
import ai.helper2.api.generated.model.RelationshipTargetGroupDecisionDto;
import ai.helper2.api.generated.model.RelationshipTargetGroupDto;
import ai.helper2.api.generated.model.RelationshipTargetGroupOutcomeDto;
import ai.helper2.api.generated.model.RelationshipTargetGroupPageDto;
import ai.helper2.catalog.KnownTarget;
import ai.helper2.catalog.RelationshipTargets;
import ai.helper2.platform.BadRequestException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class RelationshipTargetsController implements RelationshipTargetsApi {

  private final RelationshipTargets targets;
  private final DecideRelationshipTargetGroup decide;

  RelationshipTargetsController(RelationshipTargets targets, DecideRelationshipTargetGroup decide) {
    this.targets = targets;
    this.decide = decide;
  }

  @Override
  public ResponseEntity<RelationshipTargetGroupPageDto> listRelationshipTargetGroups(
      Integer page, Integer size) {
    return ResponseEntity.ok(
        new RelationshipTargetGroupPageDto(
            targets.openGroups(Math.multiplyExact(page, size), size).stream()
                .map(RelationshipTargetsController::dto)
                .toList(),
            Math.toIntExact(targets.openGroupCount())));
  }

  @Override
  public ResponseEntity<RelationshipTargetGroupOutcomeDto> decideRelationshipTargetGroup(
      RelationshipTargetGroupDecisionDto body) {
    DecideRelationshipTargetGroup.Outcome outcome =
        decide.decide(body.getKey(), choice(body), adminId(), body.getNote());
    return ResponseEntity.ok(
        new RelationshipTargetGroupOutcomeDto(
            outcome.batch(), outcome.decided(), outcome.skipped(), outcome.left()));
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

  private static String adminId() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }
}
