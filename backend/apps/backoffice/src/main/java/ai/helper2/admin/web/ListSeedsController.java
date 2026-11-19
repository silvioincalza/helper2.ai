package ai.helper2.admin.web;

import ai.helper2.api.generated.ListSeedsApi;
import ai.helper2.api.generated.model.ListSeedAddDto;
import ai.helper2.api.generated.model.ListSeedEntryDto;
import ai.helper2.api.generated.model.ListSeedRemoveDto;
import ai.helper2.api.generated.model.ListSeedsDto;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.NotFoundException;
import ai.helper2.platform.ProblemType;
import ai.helper2.scout.ListAddresses;
import ai.helper2.scout.ListSeed;
import ai.helper2.scout.usecase.ManageListSeeds;
import ai.helper2.shared.UtcTimes;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class ListSeedsController implements ListSeedsApi {

  private final ManageListSeeds seeds;

  ListSeedsController(ManageListSeeds seeds) {
    this.seeds = seeds;
  }

  @Override
  public ResponseEntity<ListSeedsDto> listListSeeds() {
    return ResponseEntity.ok(
        new ListSeedsDto(
            seeds.list().stream().map(ListSeedsController::dto).toList(),
            ManageListSeeds.MAX_SEEDS));
  }

  @Override
  public ResponseEntity<ListSeedEntryDto> addListSeed(ListSeedAddDto body) {
    return switch (seeds.add(Admins.id(), body.getAddress(), body.getReason())) {
      case ManageListSeeds.Added.Done d ->
          ResponseEntity.status(HttpStatus.CREATED).body(dto(d.getSeed()));
      case ManageListSeeds.Added.Invalid i -> throw invalid(i.getProblem());
      case ManageListSeeds.Added.AlreadyListed a ->
          throw new ApiProblem(
              ProblemType.DUPLICATE,
              a.getCurrent().getRepository() + " is a list to read already.");
      case ManageListSeeds.Added.Full f ->
          throw new ApiProblem(
              ProblemType.INVALID_REQUEST,
              "At most " + ManageListSeeds.MAX_SEEDS + " lists are read. Remove one first.");
      case ManageListSeeds.Added.BadReason r -> throw badReason();
    };
  }

  @Override
  public ResponseEntity<Void> removeListSeed(
      String owner, String repository, @Nullable ListSeedRemoveDto body) {
    String reason = body == null ? null : body.getReason();
    String name = owner + "/" + repository;
    return switch (seeds.remove(Admins.id(), name, reason)) {
      case ManageListSeeds.Removed.Done d -> ResponseEntity.noContent().build();
      case ManageListSeeds.Removed.NotFound n ->
          throw new NotFoundException(name + " is not a list to read.");
      case ManageListSeeds.Removed.BadReason r -> throw badReason();
    };
  }

  private static ApiProblem invalid(ListAddresses.Problem problem) {
    return new ApiProblem(
        ProblemType.INVALID_REQUEST,
        switch (problem) {
          case EMPTY -> "address: enter a GitHub repository, such as owner/awesome-llm.";
          case NOT_A_GITHUB_REPOSITORY ->
              "address: a GitHub repository, as owner/name or https://github.com/owner/name; no"
                  + " file path, branch or query.";
        });
  }

  private static ApiProblem badReason() {
    return new ApiProblem(
        ProblemType.INVALID_REQUEST,
        "reason: one plain line of at most " + ManageListSeeds.MAX_REASON + " characters.");
  }

  private static ListSeedEntryDto dto(ListSeed s) {
    return new ListSeedEntryDto(s.getRepository(), s.getAddedBy(), UtcTimes.odt(s.getAddedAt()))
        .reason(s.getReason());
  }
}
