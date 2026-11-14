package ai.helper2.admin.web;

import ai.helper2.admin.config.AdminProperties;
import ai.helper2.admin.usecase.GetOverview;
import ai.helper2.api.common.model.AudiencePathDto;
import ai.helper2.api.common.model.WebCreditsDto;
import ai.helper2.api.generated.OverviewApi;
import ai.helper2.api.generated.model.AdminOverviewDto;
import ai.helper2.api.generated.model.BudgetTodayDto;
import ai.helper2.api.generated.model.ExampleCheckOutcomeDto;
import ai.helper2.api.generated.model.ExampleOutcomeCountDto;
import ai.helper2.api.generated.model.ExamplePathCountDto;
import ai.helper2.api.generated.model.HomeExampleCountsDto;
import ai.helper2.api.generated.model.ReviewKindCountDto;
import ai.helper2.api.generated.model.ReviewKindDto;
import ai.helper2.examples.PoolCounts;
import ai.helper2.shared.UtcTimes;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class OverviewController implements OverviewApi {

  private final GetOverview overview;
  private final URI grafanaUrl;

  OverviewController(GetOverview overview, AdminProperties admin) {
    this.overview = overview;
    this.grafanaUrl = grafanaLink(admin.grafanaUrl());
  }

  static URI grafanaLink(String value) {
    if (value.isBlank()) {
      return null;
    }
    URI uri = URI.create(value.strip());
    String scheme =
        uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
    if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null) {
      throw new IllegalStateException(
          "H2_ADMIN_GRAFANA_URL must be an absolute http(s) URL with a host.");
    }
    return uri;
  }

  @Override
  public ResponseEntity<AdminOverviewDto> getAdminOverview() {
    GetOverview.Overview o = overview.get();
    AdminOverviewDto dto =
        new AdminOverviewDto(
                UtcTimes.odt(o.generatedAt()),
                o.openReviewItems().entrySet().stream()
                    .map(e ->
                        new ReviewKindCountDto(
                            ReviewKindDto.fromValue(e.getKey().name()),
                            Math.toIntExact(e.getValue())))
                    .toList(),
                Math.toIntExact(o.enrichmentBacklog()),
                o.budgets().stream()
                    .map(b ->
                        new BudgetTodayDto(b.name(), b.dailyLimit())
                            .usedToday(
                                b.usedToday() == null ? null : Math.toIntExact(b.usedToday())))
                    .toList(),
                o.recentRuns().stream().map(AdminController::dto).toList(),
                o.failedRunsLast24h(),
                dto(o.homeExamples()),
                o.overridesActive())
            .grafanaUrl(grafanaUrl);
    if (o.webCredits() != null) {
      dto.webCredits(
          new WebCreditsDto(o.webCredits().getUsedToday()).dailyCap(o.webCredits().getDailyCap()));
    }
    return ResponseEntity.ok(dto);
  }

  static HomeExampleCountsDto dto(PoolCounts counts) {
    return new HomeExampleCountsDto(
        counts.getShown().entrySet().stream()
            .map(e ->
                new ExamplePathCountDto(AudiencePathDto.fromValue(e.getKey().name()), e.getValue()))
            .toList(),
        counts.getHidden().entrySet().stream()
            .map(e ->
                new ExampleOutcomeCountDto(
                    ExampleCheckOutcomeDto.fromValue(e.getKey().name()), e.getValue()))
            .toList(),
        counts.getPaused());
  }
}
