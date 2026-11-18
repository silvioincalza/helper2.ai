package ai.helper2.admin.web;

import ai.helper2.api.generated.ShadowLibrariesApi;
import ai.helper2.api.generated.model.ShadowLibrariesDto;
import ai.helper2.api.generated.model.ShadowLibraryAddDto;
import ai.helper2.api.generated.model.ShadowLibraryEntryDto;
import ai.helper2.api.generated.model.ShadowLibraryRemoveDto;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.NotFoundException;
import ai.helper2.platform.ProblemType;
import ai.helper2.search.ShadowLibrary;
import ai.helper2.search.ShadowLibraryNames;
import ai.helper2.search.usecase.ManageShadowLibraries;
import ai.helper2.shared.UtcTimes;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class ShadowLibrariesController implements ShadowLibrariesApi {

  private final ManageShadowLibraries libraries;

  ShadowLibrariesController(ManageShadowLibraries libraries) {
    this.libraries = libraries;
  }

  @Override
  public ResponseEntity<ShadowLibrariesDto> listShadowLibraries() {
    return ResponseEntity.ok(
        new ShadowLibrariesDto(
            libraries.list().stream().map(ShadowLibrariesController::dto).toList(),
            ManageShadowLibraries.MAX_NAMES));
  }

  @Override
  public ResponseEntity<ShadowLibraryEntryDto> addShadowLibrary(ShadowLibraryAddDto body) {
    return switch (libraries.add(adminId(), body.getName(), body.getReason())) {
      case ManageShadowLibraries.Added.Done d ->
          ResponseEntity.status(HttpStatus.CREATED).body(dto(d.getLibrary()));
      case ManageShadowLibraries.Added.Invalid i -> throw invalid(i.getProblem());
      case ManageShadowLibraries.Added.AlreadyListed a ->
          throw new ApiProblem(
              ProblemType.DUPLICATE, a.getCurrent().getName() + " is in the list already.");
      case ManageShadowLibraries.Added.Full f ->
          throw new ApiProblem(
              ProblemType.INVALID_REQUEST,
              "The list holds at most "
                  + ManageShadowLibraries.MAX_NAMES
                  + " names. Remove one first.");
      case ManageShadowLibraries.Added.BadReason r -> throw badReason();
      default -> throw new IllegalStateException("unknown outcome");
    };
  }

  @Override
  public ResponseEntity<Void> removeShadowLibrary(
      String name, @Nullable ShadowLibraryRemoveDto body) {
    String reason = body == null ? null : body.getReason();
    return switch (libraries.remove(adminId(), name, reason)) {
      case ManageShadowLibraries.Removed.Done d -> ResponseEntity.noContent().build();
      case ManageShadowLibraries.Removed.NotFound n ->
          throw new NotFoundException(name + " is not in the list.");
      case ManageShadowLibraries.Removed.BadReason r -> throw badReason();
      default -> throw new IllegalStateException("unknown outcome");
    };
  }

  private static ApiProblem invalid(ShadowLibraryNames.Problem problem) {
    return new ApiProblem(
        ProblemType.INVALID_REQUEST,
        switch (problem) {
          case EMPTY -> "name: enter a name, such as libgen.";
          case NOT_A_NAME ->
              "name: one part of a host name, such as libgen: letters, digits and hyphens, no dot.";
          case TOO_SHORT ->
              "name: at least "
                  + ShadowLibraryNames.MIN_LENGTH
                  + " characters; a shorter one matches too many sites.";
          case TOO_COMMON -> "name: many sites use this part of a host name, such as www or blog.";
        });
  }

  private static ApiProblem badReason() {
    return new ApiProblem(
        ProblemType.INVALID_REQUEST,
        "reason: one plain line of at most " + ManageShadowLibraries.MAX_REASON + " characters.");
  }

  private static String adminId() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }

  private static ShadowLibraryEntryDto dto(ShadowLibrary l) {
    return new ShadowLibraryEntryDto(l.getName(), l.getAddedBy(), UtcTimes.odt(l.getAddedAt()))
        .reason(l.getReason());
  }
}
