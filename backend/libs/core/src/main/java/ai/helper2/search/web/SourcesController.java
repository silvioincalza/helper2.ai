package ai.helper2.search.web;

import ai.helper2.api.common.SourcesApi;
import ai.helper2.api.common.model.CircuitStateDto;
import ai.helper2.api.common.model.ResourceTypeDto;
import ai.helper2.api.common.model.SourceFamilyDto;
import ai.helper2.api.common.model.SourceInfoDto;
import ai.helper2.api.common.model.SourceModeDto;
import ai.helper2.api.common.model.SourceOutcomeDto;
import ai.helper2.api.common.model.WebCreditsDto;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.usecase.ListSources;
import ai.helper2.search.usecase.SourceOverview;
import ai.helper2.shared.UtcTimes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class SourcesController implements SourcesApi {

  private final ListSources sources;
  private final Clock clock;

  SourcesController(ListSources sources, Clock clock) {
    this.sources = sources;
    this.clock = clock;
  }

  @Override
  public ResponseEntity<List<SourceInfoDto>> listSources() {
    Instant now = clock.instant();
    return ResponseEntity.ok(sources.list().stream().map(o -> info(o, now)).toList());
  }

  private static SourceInfoDto info(SourceOverview o, Instant now) {
    SourceDescriptor d = o.getDescriptor();
    SourceInfoDto dto =
        new SourceInfoDto(
                d.getId(),
                d.getName(),
                d.getShortName(),
                d.getExternal(),
                SourceModeDto.fromValue(d.getMode().name()),
                d.getEnabled(),
                d.getMetered(),
                d.getHomepage(),
                d.getResourceTypes().stream()
                    .map(t -> ResourceTypeDto.fromValue(t.name()))
                    .sorted()
                    .toList())
            .termsUrl(d.getTermsUrl())
            .attribution(d.getAttribution())
            .family(d.getFamily() == null ? null : SourceFamilyDto.fromValue(d.getFamily()))
            .responseTimeMs(o.getResponseTime() == null ? null : o.getResponseTime().toMillis())
            .newThisWeek(o.getNewThisWeek());
    if (o.getLast() != null) {
      dto.lastOutcome(SourceOutcomeDto.fromValue(o.getLast().getOutcome().name()))
          .lastCheckedAt(UtcTimes.odt(o.getLast().getCheckedAt()))
          .lastCheckedSecondsAgo(secondsBetween(o.getLast().getCheckedAt(), now));
    }
    if (o.getCircuit() != null) {
      dto.circuit(CircuitStateDto.fromValue(o.getCircuit().state().name()))
          .circuitOpenedAt(UtcTimes.odt(o.getCircuit().openedAt()));
    }
    if (o.getWebCredits() != null) {
      dto.webCredits(
          new WebCreditsDto(o.getWebCredits().getUsedToday())
              .dailyCap(o.getWebCredits().getDailyCap()));
    }
    return dto;
  }

  private static long secondsBetween(Instant then, Instant now) {
    return Math.max(0, Duration.between(then, now).getSeconds());
  }
}
