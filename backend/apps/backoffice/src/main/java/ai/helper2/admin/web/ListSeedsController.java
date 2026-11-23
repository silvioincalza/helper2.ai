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
import java.util.UUID;
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
              ProblemType.DUPLICATE, a.getCurrent().getAddress() + " is a list to read already.");
      case ManageListSeeds.Added.Full f ->
          throw new ApiProblem(
              ProblemType.INVALID_REQUEST,
              "At most " + ManageListSeeds.MAX_SEEDS + " lists are read. Remove one first.");
      case ManageListSeeds.Added.NeverRead n ->
          throw new ApiProblem(
              ProblemType.INVALID_REQUEST,
              "address: pages on " + n.getHost() + " are never read: " + n.getReason() + ".");
      case ManageListSeeds.Added.BadReason r -> throw badReason();
    };
  }

  @Override
  public ResponseEntity<Void> removeListSeed(UUID id, @Nullable ListSeedRemoveDto body) {
    String reason = body == null ? null : body.getReason();
    return switch (seeds.remove(Admins.id(), id, reason)) {
      case ManageListSeeds.Removed.Done d -> ResponseEntity.noContent().build();
      case ManageListSeeds.Removed.NotFound n ->
          throw new NotFoundException("No list to read has the id " + id + ".");
      case ManageListSeeds.Removed.BadReason r -> throw badReason();
    };
  }

  private static ApiProblem invalid(ListAddresses.Problem problem) {
    return new ApiProblem(
        ProblemType.INVALID_REQUEST,
        switch (problem) {
          case EMPTY ->
              "address: enter a GitHub repository, such as owner/awesome-llm, or the https address"
                  + " of a web page.";
          case NOT_A_GITHUB_REPOSITORY ->
              "address: a GitHub repository, as owner/name or https://github.com/owner/name; no"
                  + " file path, branch or query.";
          case NOT_HTTPS -> "address: a web page is read over https only.";
          case NOT_A_PUBLIC_HOST ->
              "address: a web page needs a public host name; no IP address, private name or"
                  + " punycode.";
          case NOT_A_PLAIN_PAGE ->
              "address: a web page address of at most "
                  + ListAddresses.MAX_LENGTH
                  + " characters, with no user info, port or fragment.";
        });
  }

  private static ApiProblem badReason() {
    return new ApiProblem(
        ProblemType.INVALID_REQUEST,
        "reason: one plain line of at most " + ManageListSeeds.MAX_REASON + " characters.");
  }

  private static ListSeedEntryDto dto(ListSeed s) {
    return new ListSeedEntryDto(
            s.getId(),
            ListSeedEntryDto.KindEnum.fromValue(s.getKind().name()),
            ListSeedEntryDto.OriginEnum.fromValue(s.getOrigin().name()),
            s.getAddress(),
            s.getAddedBy(),
            UtcTimes.odt(s.getAddedAt()))
        .reason(s.getReason());
  }
}
