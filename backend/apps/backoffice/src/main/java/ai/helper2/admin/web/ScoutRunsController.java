package ai.helper2.admin.web;

import ai.helper2.api.generated.ScoutRunsApi;
import ai.helper2.api.generated.model.ScoutRunDto;
import ai.helper2.api.generated.model.ScoutRunRequestDto;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.NotFoundException;
import ai.helper2.platform.ProblemType;
import ai.helper2.scout.RunSummary;
import ai.helper2.scout.usecase.ListScoutRuns;
import ai.helper2.scout.usecase.StartScoutRun;
import ai.helper2.scout.usecase.UnknownSource;
import ai.helper2.shared.UtcTimes;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class ScoutRunsController implements ScoutRunsApi {

  private final StartScoutRun startRun;
  private final ListScoutRuns scoutRuns;

  ScoutRunsController(StartScoutRun startRun, ListScoutRuns scoutRuns) {
    this.startRun = startRun;
    this.scoutRuns = scoutRuns;
  }

  @Override
  public ResponseEntity<List<ScoutRunDto>> listScoutRuns() {
    return ResponseEntity.ok(scoutRuns.latest().stream().map(ScoutRunsController::dto).toList());
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
        .body(runs.stream().map(ScoutRunsController::dto).toList());
  }

  private static String adminId() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }

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
