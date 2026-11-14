package ai.helper2.catalog.web;

import ai.helper2.api.generated.IssueReportsApi;
import ai.helper2.api.generated.model.IssueReportAcceptedDto;
import ai.helper2.api.generated.model.IssueReportRequestDto;
import ai.helper2.catalog.usecase.ReportIssue;
import ai.helper2.platform.BadRequestException;
import ai.helper2.platform.NotFoundException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class IssueReportsController implements IssueReportsApi {

  private final ReportIssue reports;

  IssueReportsController(ReportIssue reports) {
    this.reports = reports;
  }

  @Override
  public ResponseEntity<IssueReportAcceptedDto> reportResource(
      UUID id, IssueReportRequestDto body) {
    String message = body.getMessage().strip();
    if (message.length() < 5) {
      throw new BadRequestException("message too short");
    }
    UUID reportId =
        reports
            .submit(
                id,
                ReportIssue.Kind.valueOf(body.getKind().getValue()),
                body.getField() == null || body.getField().isBlank()
                    ? null
                    : body.getField().strip(),
                message)
            .orElseThrow(
                () -> new NotFoundException("Resource not found in the helper2.ai catalog."));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(new IssueReportAcceptedDto(reportId));
  }
}
