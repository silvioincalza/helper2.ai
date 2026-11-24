package ai.searchavo.admin.web;

import ai.searchavo.api.common.model.AudiencePathDto;
import ai.searchavo.api.generated.ExamplesAdminApi;
import ai.searchavo.api.generated.model.AdminHomeExampleDto;
import ai.searchavo.api.generated.model.ExampleCheckDto;
import ai.searchavo.api.generated.model.ExampleCheckOutcomeDto;
import ai.searchavo.api.generated.model.ExampleDraftsDto;
import ai.searchavo.api.generated.model.ExampleStatusDto;
import ai.searchavo.api.generated.model.ExpectedResourceDto;
import ai.searchavo.api.generated.model.HomeExampleChangeDto;
import ai.searchavo.api.generated.model.HomeExampleInputDto;
import ai.searchavo.examples.CheckOutcome;
import ai.searchavo.examples.CheckResult;
import ai.searchavo.examples.DraftsUnavailable;
import ai.searchavo.examples.ExampleStatus;
import ai.searchavo.examples.HomeExample;
import ai.searchavo.examples.usecase.AddHomeExample;
import ai.searchavo.examples.usecase.ChangeHomeExample;
import ai.searchavo.examples.usecase.DraftHomeExamples;
import ai.searchavo.examples.usecase.ExampleView;
import ai.searchavo.examples.usecase.ListAdminHomeExamples;
import ai.searchavo.examples.usecase.RemoveHomeExample;
import ai.searchavo.platform.ApiProblem;
import ai.searchavo.platform.NotFoundException;
import ai.searchavo.platform.ProblemType;
import ai.searchavo.shared.AudiencePath;
import ai.searchavo.shared.UtcTimes;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class HomeExamplesAdminController implements ExamplesAdminApi {

  private final ListAdminHomeExamples list;
  private final AddHomeExample add;
  private final ChangeHomeExample change;
  private final RemoveHomeExample remove;
  private final DraftHomeExamples drafts;

  HomeExamplesAdminController(
      ListAdminHomeExamples list,
      AddHomeExample add,
      ChangeHomeExample change,
      RemoveHomeExample remove,
      DraftHomeExamples drafts) {
    this.list = list;
    this.add = add;
    this.change = change;
    this.remove = remove;
    this.drafts = drafts;
  }

  @Override
  public ResponseEntity<List<AdminHomeExampleDto>> listAdminHomeExamples(
      @Nullable ExampleCheckOutcomeDto outcome) {
    CheckOutcome filter = outcome == null ? null : CheckOutcome.valueOf(outcome.getValue());
    return ResponseEntity.ok(
        list.list(filter).stream().map(HomeExamplesAdminController::dto).toList());
  }

  @Override
  public ResponseEntity<AdminHomeExampleDto> addHomeExample(HomeExampleInputDto body) {
    return switch (add.add(
        Admins.id(),
        body.getLabel(),
        body.getQuery(),
        AudiencePath.valueOf(body.getPath().getValue()),
        body.getExpectedResourceIds())) {
      case AddHomeExample.Outcome.Added a ->
          ResponseEntity.status(HttpStatus.CREATED).body(dto(a.example()));
      case AddHomeExample.Outcome.Invalid i -> throw invalid(i.problems());
      case AddHomeExample.Outcome.Duplicate d -> throw duplicate();
    };
  }

  @Override
  public ResponseEntity<AdminHomeExampleDto> changeHomeExample(UUID id, HomeExampleChangeDto body) {
    return switch (change.change(
        Admins.id(),
        id,
        body.getLabel(),
        body.getQuery(),
        AudiencePath.valueOf(body.getPath().getValue()),
        body.getExpectedResourceIds(),
        ExampleStatus.valueOf(body.getStatus().getValue()),
        body.getVersion())) {
      case ChangeHomeExample.Outcome.Changed c -> ResponseEntity.ok(dto(c.example()));
      case ChangeHomeExample.Outcome.Invalid i -> throw invalid(i.problems());
      case ChangeHomeExample.Outcome.NotFound n ->
          throw new NotFoundException("Example not found.");
      case ChangeHomeExample.Outcome.Conflict c ->
          throw new ApiProblem(
              ProblemType.VERSION_CONFLICT,
              "Someone changed this example first. Reload it and make your change again.");
      case ChangeHomeExample.Outcome.Duplicate d -> throw duplicate();
    };
  }

  @Override
  public ResponseEntity<Void> removeHomeExample(UUID id) {
    remove.remove(Admins.id(), id);
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<ExampleDraftsDto> draftHomeExamples() {
    try {
      DraftHomeExamples.Drafted drafted = drafts.draft(Admins.id());
      return ResponseEntity.status(HttpStatus.ACCEPTED)
          .body(new ExampleDraftsDto(drafted.count(), drafted.model()));
    } catch (DraftsUnavailable e) {
      throw new ApiProblem(ProblemType.UNAVAILABLE, draftsUnavailable(e.getReason()));
    }
  }

  private static String draftsUnavailable(DraftsUnavailable.Reason reason) {
    return switch (reason) {
      case NO_MODEL -> "Drafts are off: no model is configured, or SA_EXAMPLES_DRAFT_CALLS is 0.";
      case BUDGET -> "Today's draft runs are used up. Try again tomorrow (UTC).";
      case PROVIDER -> "The model cannot answer now. Try again later.";
      case UNREADABLE -> "The model's answer could not be read. Try again.";
    };
  }

  private static ApiProblem invalid(List<String> problems) {
    return new ApiProblem(ProblemType.INVALID_REQUEST, String.join("; ", problems) + ".");
  }

  private static ApiProblem duplicate() {
    return new ApiProblem(
        ProblemType.DUPLICATE, "Another example already has this label or this query.");
  }

  private static AdminHomeExampleDto dto(ExampleView view) {
    HomeExample e = view.example();
    return new AdminHomeExampleDto(
            e.getId(),
            e.getLabel(),
            e.getQuery(),
            AudiencePathDto.fromValue(e.getPath().name()),
            ExampleStatusDto.fromValue(e.getStatus().name()),
            AdminHomeExampleDto.OriginEnum.fromValue(e.getOrigin().name()),
            view.expected().stream()
                .map(x -> new ExpectedResourceDto(x.id()).title(x.title()))
                .toList(),
            dto(e.getCheck()),
            e.getVersion(),
            UtcTimes.odt(e.getUpdatedAt()))
        .model(e.getModel());
  }

  private static ExampleCheckDto dto(CheckResult c) {
    return new ExampleCheckDto(ExampleCheckOutcomeDto.fromValue(c.getOutcome().name()))
        .bestRank(c.getBestRank())
        .resultCount(c.getResultCount())
        .checkedAt(UtcTimes.odt(c.getCheckedAt()));
  }
}
