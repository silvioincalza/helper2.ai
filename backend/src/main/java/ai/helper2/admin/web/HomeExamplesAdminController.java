package ai.helper2.admin.web;

import ai.helper2.api.generated.ExamplesAdminApi;
import ai.helper2.api.generated.model.AdminHomeExampleDto;
import ai.helper2.api.generated.model.AudiencePathDto;
import ai.helper2.api.generated.model.ExampleCheckDto;
import ai.helper2.api.generated.model.ExampleCheckOutcomeDto;
import ai.helper2.api.generated.model.ExampleStatusDto;
import ai.helper2.api.generated.model.ExpectedResourceDto;
import ai.helper2.api.generated.model.HomeExampleChangeDto;
import ai.helper2.api.generated.model.HomeExampleInputDto;
import ai.helper2.examples.CheckOutcome;
import ai.helper2.examples.CheckResult;
import ai.helper2.examples.ExampleStatus;
import ai.helper2.examples.HomeExample;
import ai.helper2.examples.usecase.AddHomeExample;
import ai.helper2.examples.usecase.ChangeHomeExample;
import ai.helper2.examples.usecase.ExampleView;
import ai.helper2.examples.usecase.ListAdminHomeExamples;
import ai.helper2.examples.usecase.RemoveHomeExample;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.NotFoundException;
import ai.helper2.platform.ProblemType;
import ai.helper2.shared.AudiencePath;
import ai.helper2.shared.UtcTimes;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The back office's Examples part (ADR 0049): the examples the home page picks from. */
@RestController
@RequestMapping("/api/v1")
class HomeExamplesAdminController implements ExamplesAdminApi {

  private final ListAdminHomeExamples list;
  private final AddHomeExample add;
  private final ChangeHomeExample change;
  private final RemoveHomeExample remove;

  HomeExamplesAdminController(
      ListAdminHomeExamples list,
      AddHomeExample add,
      ChangeHomeExample change,
      RemoveHomeExample remove) {
    this.list = list;
    this.add = add;
    this.change = change;
    this.remove = remove;
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
        adminId(),
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
        adminId(),
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
    remove.remove(adminId(), id);
    return ResponseEntity.noContent().build();
  }

  /** The rules the input breaks: our own words, never the input itself. */
  private static ApiProblem invalid(List<String> problems) {
    return new ApiProblem(ProblemType.INVALID_REQUEST, String.join("; ", problems) + ".");
  }

  private static ApiProblem duplicate() {
    return new ApiProblem(
        ProblemType.DUPLICATE, "Another example already has this label or this query.");
  }

  private static String adminId() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
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
