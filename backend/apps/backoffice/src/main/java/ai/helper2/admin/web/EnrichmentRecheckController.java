package ai.helper2.admin.web;

import ai.helper2.api.generated.EnrichmentRecheckApi;
import ai.helper2.api.generated.model.EnrichmentRecheckDto;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.ProblemType;
import ai.helper2.scout.usecase.RecheckEnrichments;
import ai.helper2.scout.usecase.RecheckResult;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
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
    RecheckResult r = recheck.runByAdmin(adminId());
    if (r == null) {
      throw new ApiProblem(
          ProblemType.RUN_IN_PROGRESS, "A Scout run is in progress. Try again later.");
    }
    return ResponseEntity.ok(
        new EnrichmentRecheckDto(
            r.getChecked(), r.getFieldsRejected(), r.getClaimsDropped(), r.getRunsRequested()));
  }

  private static String adminId() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }
}
