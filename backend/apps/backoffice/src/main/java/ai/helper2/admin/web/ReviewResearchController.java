package ai.helper2.admin.web;

import ai.helper2.api.generated.ReviewResearchApi;
import ai.helper2.api.generated.model.ReviewResearchDetailDto;
import ai.helper2.api.generated.model.ReviewResearchEstimateDto;
import ai.helper2.api.generated.model.ReviewResearchResultAttributesInnerDto;
import ai.helper2.api.generated.model.ReviewResearchResultAttributesInnerSourcesInnerDto;
import ai.helper2.api.generated.model.ReviewResearchResultDto;
import ai.helper2.api.generated.model.ReviewResearchRunDto;
import ai.helper2.api.generated.model.ReviewResearchRunsDto;
import ai.helper2.api.generated.model.ReviewResearchStartDto;
import ai.helper2.api.generated.model.ReviewResearchStepDto;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.NotFoundException;
import ai.helper2.platform.ProblemType;
import ai.helper2.research.ResearchResult;
import ai.helper2.research.ResearchRun;
import ai.helper2.research.ResearchRuns;
import ai.helper2.research.ResearchStep;
import ai.helper2.research.usecase.CancelResearch;
import ai.helper2.research.usecase.StartResearch;
import ai.helper2.shared.UtcTimes;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class ReviewResearchController implements ReviewResearchApi {

  private final StartResearch start;
  private final CancelResearch cancel;
  private final ResearchRuns runs;

  ReviewResearchController(StartResearch start, CancelResearch cancel, ResearchRuns runs) {
    this.start = start;
    this.cancel = cancel;
    this.runs = runs;
  }

  @Override
  public ResponseEntity<ReviewResearchRunsDto> startReviewResearch(ReviewResearchStartDto body) {
    boolean allOpen = Boolean.TRUE.equals(body.getAllOpen());
    List<UUID> ids = body.getItemIds() == null ? List.of() : body.getItemIds();
    if (allOpen == !ids.isEmpty()) {
      throw new ApiProblem(ProblemType.INVALID_REQUEST, "Give itemIds or allOpen, not both.");
    }
    List<ResearchRun> queued =
        allOpen ? start.startAllOpen(adminId()) : start.start(ids, adminId());
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(runsDto(queued));
  }

  @Override
  public ResponseEntity<ReviewResearchRunsDto> listReviewResearch(List<UUID> itemId) {
    return ResponseEntity.ok(runsDto(runs.latest(itemId)));
  }

  @Override
  public ResponseEntity<ReviewResearchEstimateDto> estimateReviewResearch() {
    StartResearch.Estimate e = start.estimateAllOpen();
    return ResponseEntity.ok(new ReviewResearchEstimateDto(e.items(), e.maxModelCalls()));
  }

  @Override
  public ResponseEntity<ReviewResearchDetailDto> getReviewResearch(UUID runId) {
    ResearchRun run = found(runId);
    ReviewResearchDetailDto dto =
        new ReviewResearchDetailDto(
            runDto(run),
            runs.steps(runId, 0).stream().map(ReviewResearchController::stepDto).toList());
    if (run.result() != null) {
      dto.result(resultDto(run.result()));
    }
    return ResponseEntity.ok(dto);
  }

  @Override
  public ResponseEntity<Void> cancelReviewResearch(UUID runId) {
    found(runId);
    if (!cancel.cancel(runId)) {
      throw new ApiProblem(ProblemType.ALREADY_DECIDED, "The run has ended.");
    }
    return ResponseEntity.noContent().build();
  }

  private ResearchRun found(UUID runId) {
    return runs.find(runId).orElseThrow(() -> new NotFoundException("Research run not found."));
  }

  static ReviewResearchRunsDto runsDto(List<ResearchRun> list) {
    return new ReviewResearchRunsDto(list.stream().map(ReviewResearchController::runDto).toList());
  }

  static ReviewResearchRunDto runDto(ResearchRun r) {
    return new ReviewResearchRunDto(
            r.id(),
            r.itemId(),
            ReviewResearchRunDto.StatusEnum.fromValue(r.status().name()),
            r.model(),
            UtcTimes.odt(r.requestedAt()),
            r.steps(),
            r.promptTokens(),
            r.completionTokens())
        .startedAt(UtcTimes.odt(r.startedAt()))
        .finishedAt(UtcTimes.odt(r.finishedAt()))
        .verdict(
            r.result() == null
                ? null
                : ReviewResearchRunDto.VerdictEnum.fromValue(r.result().verdict().name()))
        .error(r.error());
  }

  static ReviewResearchStepDto stepDto(ResearchStep s) {
    return new ReviewResearchStepDto(
            s.seq(),
            ReviewResearchStepDto.KindEnum.fromValue(s.kind().name()),
            s.name(),
            s.request(),
            s.durationMs(),
            UtcTimes.odt(s.at()))
        .response(s.response())
        .error(s.error())
        .promptTokens(s.promptTokens())
        .completionTokens(s.completionTokens());
  }

  private static ReviewResearchResultDto resultDto(ResearchResult r) {
    return new ReviewResearchResultDto(
            ReviewResearchResultDto.VerdictEnum.fromValue(r.verdict().name()),
            r.reason(),
            true,
            r.attributes().stream().map(ReviewResearchController::attributeDto).toList(),
            r.dropped())
        .duplicateOf(r.duplicateOf());
  }

  private static ReviewResearchResultAttributesInnerDto attributeDto(ResearchResult.Attribute a) {
    return new ReviewResearchResultAttributesInnerDto(
            a.name(),
            a.proposed(),
            ReviewResearchResultAttributesInnerDto.StatusEnum.fromValue(a.status().name()),
            a.sources().stream()
                .map(s ->
                    new ReviewResearchResultAttributesInnerSourcesInnerDto(
                        s.evidenceId(),
                        s.tool(),
                        s.url(),
                        s.excerpt(),
                        s.value(),
                        s.authoritative()))
                .toList())
        .current(a.current());
  }

  private static String adminId() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }
}
