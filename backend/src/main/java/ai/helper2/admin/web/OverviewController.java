package ai.helper2.admin.web;

import ai.helper2.admin.usecase.GetOverview;
import ai.helper2.api.generated.OverviewApi;
import ai.helper2.api.generated.model.AdminOverviewDto;
import ai.helper2.api.generated.model.BudgetTodayDto;
import ai.helper2.api.generated.model.ReviewKindCountDto;
import ai.helper2.api.generated.model.ReviewKindDto;
import ai.helper2.api.generated.model.WebCreditsDto;
import ai.helper2.shared.UtcTimes;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/v1/admin/overview (ADMIN only, admin port): the back office landing page. */
@RestController
@RequestMapping("/api/v1")
class OverviewController implements OverviewApi {

    private final GetOverview overview;
    private final URI grafanaUrl;

    OverviewController(GetOverview overview, @Value("${helper2.admin.grafana-url:}") String grafanaUrl) {
        this.overview = overview;
        this.grafanaUrl = grafanaUrl.isBlank() ? null : URI.create(grafanaUrl);
    }

    @Override
    public ResponseEntity<AdminOverviewDto> getAdminOverview() {
        GetOverview.Overview o = overview.get();
        AdminOverviewDto dto = new AdminOverviewDto(
            UtcTimes.odt(o.generatedAt()),
            o.openReviewItems().entrySet().stream()
                .map(e -> new ReviewKindCountDto(ReviewKindDto.fromValue(e.getKey().name()), Math.toIntExact(e.getValue())))
                .toList(),
            Math.toIntExact(o.enrichmentBacklog()),
            o.budgets().stream()
                .map(b -> new BudgetTodayDto(b.name(), b.dailyLimit()).usedToday(b.usedToday() == null ? null : Math.toIntExact(b.usedToday())))
                .toList(),
            o.recentRuns().stream().map(AdminController::dto).toList(),
            o.failedRunsLast24h())
            .grafanaUrl(grafanaUrl);
        if (o.webCredits() != null) {
            dto.webCredits(new WebCreditsDto(o.webCredits().getUsedToday()).dailyCap(o.webCredits().getDailyCap()));
        }
        return ResponseEntity.ok(dto);
    }
}
