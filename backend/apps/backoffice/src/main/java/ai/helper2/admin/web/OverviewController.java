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
import ai.helper2.api.generated.model.ValidationIssueCountDto;
import ai.helper2.api.generated.model.ValidationIssueSummaryDto;
import ai.helper2.catalog.enrichment.ValidationIssues;
import ai.helper2.examples.PoolCounts;
import ai.helper2.shared.UtcTimes;
import java.net.URI;
import java.net.URISyntaxException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class OverviewController implements OverviewApi {

  private final GetOverview overview;
  private final @Nullable URI grafanaUrl;

  OverviewController(GetOverview overview, AdminProperties admin) {
    this.overview = overview;
    this.grafanaUrl = grafanaLink(admin.grafanaUrl());
  }

  static @Nullable URI grafanaLink(String value) {
    if (value.isBlank()) {
      return null;
    }
    URI link = parsed(value.strip());
    if (link == null || !isHttp(link) || link.getHost() == null || carriesMoreThanAPath(link)) {
      throw new IllegalStateException(
          "H2_ADMIN_GRAFANA_URL must be an absolute http(s) URL with a host and a path only:"
              + " no user info, no query, no fragment.");
    }
    return link;
  }

  private static @Nullable URI parsed(String value) {
    try {
      return new URI(value);
    } catch (URISyntaxException malformed) {
      return null;
    }
  }

  private static boolean isHttp(URI link) {
    String scheme = link.getScheme();
    return scheme != null && (scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"));
  }

  private static boolean carriesMoreThanAPath(URI link) {
    return link.getRawUserInfo() != null
        || link.getRawQuery() != null
        || link.getRawFragment() != null;
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
                o.recentRuns().stream().map(ScoutRunsController::dto).toList(),
                o.failedRunsLast24h(),
                dto(o.homeExamples()),
                o.overridesActive())
            .grafanaUrl(grafanaUrl)
            .validationIssues(dto(o.validationIssues()));
    if (o.webCredits() != null) {
      dto.webCredits(
          new WebCreditsDto(o.webCredits().getUsedToday()).dailyCap(o.webCredits().getDailyCap()));
    }
    return ResponseEntity.ok(dto);
  }

  static ValidationIssueSummaryDto dto(ValidationIssues.Summary issues) {
    return new ValidationIssueSummaryDto(
        Math.toIntExact(GetOverview.ISSUE_WINDOW.toDays()),
        issues.runs(),
        issues.top().stream()
            .map(c -> new ValidationIssueCountDto(c.subject(), c.rule(), c.runs()))
            .toList());
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
