package ai.searchavo.admin.web;

import ai.searchavo.api.generated.KnownTargetsApi;
import ai.searchavo.api.generated.model.KnownTargetEntryDto;
import ai.searchavo.api.generated.model.KnownTargetKindDto;
import ai.searchavo.api.generated.model.KnownTargetWriteDto;
import ai.searchavo.api.generated.model.KnownTargetsDto;
import ai.searchavo.catalog.KnownTarget;
import ai.searchavo.catalog.KnownTargetList.Entry;
import ai.searchavo.catalog.usecase.ManageKnownTargets;
import ai.searchavo.platform.ApiProblem;
import ai.searchavo.platform.NotFoundException;
import ai.searchavo.platform.ProblemType;
import ai.searchavo.shared.UtcTimes;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
        .body(
            dto(saved(targets.add(Admins.id(), kind(body), body.getName(), body.getOtherNames()))));
  }

  @Override
  public ResponseEntity<KnownTargetEntryDto> changeKnownTarget(UUID id, KnownTargetWriteDto body) {
    return ResponseEntity.ok(
        dto(
            saved(
                targets.change(
                    Admins.id(), id, kind(body), body.getName(), body.getOtherNames()))));
  }

  @Override
  public ResponseEntity<Void> removeKnownTarget(UUID id) {
    if (targets.remove(Admins.id(), id) instanceof ManageKnownTargets.Removed.NotFound) {
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
          throw KnownTargetProblems.listIsFull(ManageKnownTargets.MAX_TARGETS);
      case ManageKnownTargets.Saved.NotFound n ->
          throw new NotFoundException("Known target not found.");
    };
  }

  private static KnownTarget.Kind kind(KnownTargetWriteDto body) {
    return KnownTarget.Kind.valueOf(body.getKind().getValue());
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
