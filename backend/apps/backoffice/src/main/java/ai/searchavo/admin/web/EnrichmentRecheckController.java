package ai.searchavo.admin.web;

import ai.searchavo.api.generated.EnrichmentRecheckApi;
import ai.searchavo.api.generated.model.EnrichmentRecheckDto;
import ai.searchavo.platform.ApiProblem;
import ai.searchavo.platform.ProblemType;
import ai.searchavo.scout.usecase.RecheckEnrichments;
import ai.searchavo.scout.usecase.RecheckResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class EnrichmentRecheckController implements EnrichmentRecheckApi {

  private final RecheckEnrichments recheck;

  EnrichmentRecheckController(RecheckEnrichments recheck) {
    this.recheck = recheck;
  }

  @Override
  public ResponseEntity<EnrichmentRecheckDto> recheckEnrichments() {
    RecheckResult r = recheck.runByAdmin(Admins.id());
    if (r == null) {
      throw new ApiProblem(
          ProblemType.RUN_IN_PROGRESS, "A Scout run is in progress. Try again later.");
    }
    return ResponseEntity.ok(
        new EnrichmentRecheckDto(
            r.getChecked(), r.getFieldsRejected(), r.getClaimsDropped(), r.getRunsRequested()));
  }
}
