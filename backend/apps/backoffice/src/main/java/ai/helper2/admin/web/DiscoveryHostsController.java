package ai.helper2.admin.web;

import ai.helper2.api.generated.DiscoveryHostsApi;
import ai.helper2.api.generated.model.DiscoveryHostAddDto;
import ai.helper2.api.generated.model.DiscoveryHostEntryDto;
import ai.helper2.api.generated.model.DiscoveryHostKindDto;
import ai.helper2.api.generated.model.DiscoveryHostRemoveDto;
import ai.helper2.api.generated.model.DiscoveryHostsDto;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.NotFoundException;
import ai.helper2.platform.ProblemType;
import ai.helper2.scout.DiscoveryHost;
import ai.helper2.scout.HostKind;
import ai.helper2.scout.HostNames;
import ai.helper2.scout.usecase.ManageDiscoveryHosts;
import ai.helper2.shared.UtcTimes;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Discovery's host lists (ADR 0022), ADMIN only, admin port. */
@RestController
@RequestMapping("/api/v1")
class DiscoveryHostsController implements DiscoveryHostsApi {

  private final ManageDiscoveryHosts hosts;

  DiscoveryHostsController(ManageDiscoveryHosts hosts) {
    this.hosts = hosts;
  }

  @Override
  public ResponseEntity<DiscoveryHostsDto> listDiscoveryHosts() {
    return ResponseEntity.ok(
        new DiscoveryHostsDto(
            hosts.list().stream().map(DiscoveryHostsController::dto).toList(),
            ManageDiscoveryHosts.MAX_HOSTS));
  }

  @Override
  public ResponseEntity<DiscoveryHostEntryDto> addDiscoveryHost(DiscoveryHostAddDto body) {
    HostKind kind = HostKind.valueOf(body.getKind().getValue());
    return switch (hosts.add(adminId(), body.getHost(), kind, body.getReason())) {
      case ManageDiscoveryHosts.Added.Done d ->
          ResponseEntity.status(HttpStatus.CREATED).body(dto(d.getHost()));
      case ManageDiscoveryHosts.Added.Invalid i -> throw invalid(i.getProblem());
      case ManageDiscoveryHosts.Added.AlreadyListed a ->
          throw new ApiProblem(
              ProblemType.DUPLICATE,
              a.getCurrent().getHost()
                  + " is in the "
                  + listName(a.getCurrent().getKind())
                  + " list already. Remove it there first to move it.");
      case ManageDiscoveryHosts.Added.Full f ->
          throw new ApiProblem(
              ProblemType.INVALID_REQUEST,
              "The lists hold at most "
                  + ManageDiscoveryHosts.MAX_HOSTS
                  + " hosts. Remove one first.");
      case ManageDiscoveryHosts.Added.BadReason r -> throw badReason();
      default -> throw new IllegalStateException("unknown outcome");
    };
  }

  @Override
  public ResponseEntity<Void> removeDiscoveryHost(
      String host, @Nullable DiscoveryHostRemoveDto body) {
    String reason = body == null ? null : body.getReason();
    return switch (hosts.remove(adminId(), host, reason)) {
      case ManageDiscoveryHosts.Removed.Done d -> ResponseEntity.noContent().build();
      case ManageDiscoveryHosts.Removed.NotFound n ->
          throw new NotFoundException(host + " is in no list.");
      case ManageDiscoveryHosts.Removed.BadReason r -> throw badReason();
      default -> throw new IllegalStateException("unknown outcome");
    };
  }

  private static ApiProblem invalid(HostNames.Problem problem) {
    return new ApiProblem(
        ProblemType.INVALID_REQUEST,
        switch (problem) {
          case EMPTY -> "host: enter a host name, such as techcrunch.com.";
          case NOT_A_HOST ->
              "host: only the host name, such as techcrunch.com: no https://, path or port.";
          case TOO_BROAD ->
              "host: a name and its domain, such as techcrunch.com; a domain alone matches too"
                  + " many sites.";
          case GITHUB -> "host: discovery reads GitHub by its own rule.";
        });
  }

  private static ApiProblem badReason() {
    return new ApiProblem(
        ProblemType.INVALID_REQUEST,
        "reason: one plain line of at most " + ManageDiscoveryHosts.MAX_REASON + " characters.");
  }

  private static String listName(HostKind kind) {
    return kind == HostKind.WRITING ? "writing" : "directory";
  }

  /** The signed-in administrator's provider subject (GitHub: the numeric user id). */
  private static String adminId() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }

  private static DiscoveryHostEntryDto dto(DiscoveryHost h) {
    return new DiscoveryHostEntryDto(
            h.getHost(),
            DiscoveryHostKindDto.fromValue(h.getKind().name()),
            h.getAddedBy(),
            UtcTimes.odt(h.getAddedAt()))
        .reason(h.getReason());
  }
}
