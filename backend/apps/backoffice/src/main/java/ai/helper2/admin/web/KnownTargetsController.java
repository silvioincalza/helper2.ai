package ai.helper2.admin.web;

import ai.helper2.api.generated.KnownTargetsApi;
import ai.helper2.api.generated.model.KnownTargetEntryDto;
import ai.helper2.api.generated.model.KnownTargetKindDto;
import ai.helper2.api.generated.model.KnownTargetWriteDto;
import ai.helper2.api.generated.model.KnownTargetsDto;
import ai.helper2.catalog.KnownTarget;
import ai.helper2.catalog.KnownTargetList.Entry;
import ai.helper2.catalog.usecase.ManageKnownTargets;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.NotFoundException;
import ai.helper2.platform.ProblemType;
import ai.helper2.shared.UtcTimes;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class KnownTargetsController implements KnownTargetsApi {

  private final ManageKnownTargets targets;

  KnownTargetsController(ManageKnownTargets targets) {
    this.targets = targets;
  }

  @Override
  public ResponseEntity<KnownTargetsDto> listKnownTargets() {
    return ResponseEntity.ok(
        new KnownTargetsDto(
            targets.list().stream().map(KnownTargetsController::dto).toList(),
            ManageKnownTargets.MAX_TARGETS));
  }

  @Override
  public ResponseEntity<KnownTargetEntryDto> addKnownTarget(KnownTargetWriteDto body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(dto(saved(targets.add(adminId(), kind(body), body.getName(), body.getOtherNames()))));
  }

  @Override
  public ResponseEntity<KnownTargetEntryDto> changeKnownTarget(UUID id, KnownTargetWriteDto body) {
    return ResponseEntity.ok(
        dto(
            saved(
                targets.change(adminId(), id, kind(body), body.getName(), body.getOtherNames()))));
  }

  @Override
  public ResponseEntity<Void> removeKnownTarget(UUID id) {
    if (targets.remove(adminId(), id) instanceof ManageKnownTargets.Removed.NotFound) {
      throw new NotFoundException("Known target not found.");
    }
    return ResponseEntity.noContent().build();
  }

  private static Entry saved(ManageKnownTargets.Saved saved) {
    return switch (saved) {
      case ManageKnownTargets.Saved.Done d -> d.entry();
      case ManageKnownTargets.Saved.Invalid i ->
          throw new ApiProblem(
              ProblemType.INVALID_REQUEST,
              "name: "
                  + ManageKnownTargets.MIN_NAME
                  + " to "
                  + ManageKnownTargets.MAX_NAME
                  + " characters on one plain line, and at most "
                  + ManageKnownTargets.MAX_OTHER_NAMES
                  + " other names.");
      case ManageKnownTargets.Saved.Taken t ->
          throw new ApiProblem(
              ProblemType.DUPLICATE, t.name() + " already means " + t.holder() + ".");
      case ManageKnownTargets.Saved.Full f ->
          throw new ApiProblem(
              ProblemType.DUPLICATE,
              "The list holds at most " + ManageKnownTargets.MAX_TARGETS + " known targets.");
      case ManageKnownTargets.Saved.NotFound n ->
          throw new NotFoundException("Known target not found.");
    };
  }

  private static KnownTarget.Kind kind(KnownTargetWriteDto body) {
    return KnownTarget.Kind.valueOf(body.getKind().getValue());
  }

  private static String adminId() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }

  private static KnownTargetEntryDto dto(Entry e) {
    return new KnownTargetEntryDto(
        e.id(),
        KnownTargetKindDto.fromValue(e.kind().name()),
        e.name(),
        e.otherNames(),
        e.addedBy(),
        UtcTimes.odt(e.addedAt()),
        KnownTargetEntryDto.OriginEnum.fromValue(e.origin().name()),
        e.relationships());
  }
}
