package ai.helper2.catalog.web;

import ai.helper2.api.generated.ResourcesApi;
import ai.helper2.api.generated.model.AiSummaryDto;
import ai.helper2.api.generated.model.AssertionDto;
import ai.helper2.api.generated.model.EvidenceDto;
import ai.helper2.api.generated.model.IssueReportAcceptedDto;
import ai.helper2.api.generated.model.IssueReportRequestDto;
import ai.helper2.api.generated.model.RecentResponseDto;
import ai.helper2.api.generated.model.RelationshipDto;
import ai.helper2.api.generated.model.ResourceDetailDto;
import ai.helper2.api.generated.model.ResourceTypeDto;
import ai.helper2.api.generated.model.ResultCardDto;
import ai.helper2.api.generated.model.SourceRecordInfoDto;
import ai.helper2.api.generated.model.SummaryClaimDto;
import ai.helper2.api.generated.model.UpcomingEventsResponseDto;
import ai.helper2.api.generated.model.ValueOriginDto;
import ai.helper2.api.generated.model.VerificationDto;
import ai.helper2.catalog.ResourceDetail;
import ai.helper2.catalog.usecase.GetResourceDetail;
import ai.helper2.catalog.usecase.ListRecentResources;
import ai.helper2.catalog.usecase.ListUpcomingEvents;
import ai.helper2.catalog.usecase.ReportIssue;
import ai.helper2.identity.CurrentUser;
import ai.helper2.platform.BadRequestException;
import ai.helper2.platform.NotFoundException;
import ai.helper2.search.web.CardMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class ResourcesController implements ResourcesApi {

    private final GetResourceDetail details;
    private final ListRecentResources recent;
    private final ListUpcomingEvents upcoming;
    private final ReportIssue reports;
    private final CurrentUser currentUser;
    private final Clock clock;

    ResourcesController(GetResourceDetail details, ListRecentResources recent, ListUpcomingEvents upcoming, ReportIssue reports,
                        CurrentUser currentUser, Clock clock) {
        this.details = details;
        this.recent = recent;
        this.upcoming = upcoming;
        this.clock = clock;
        this.reports = reports;
        this.currentUser = currentUser;
    }

    @Override
    public ResponseEntity<ResourceDetailDto> getResource(UUID id) {
        ResourceDetail d = details.find(id).orElseThrow(() -> new NotFoundException("Resource not found in the Helper2 index."));
        ResourceDetailDto dto = new ResourceDetailDto()
            .card(CardMapper.card(d.card()))
            .assertions(d.assertions().stream().map(a -> new AssertionDto(a.field(), a.value(),
                ValueOriginDto.fromValue(a.origin().name()), VerificationDto.fromValue(a.verification().name()), evidence(a.evidence()))
                .sourceId(a.sourceId())).toList())
            .relationships(d.relationships().stream().map(r -> new RelationshipDto(RelationshipDto.KindEnum.fromValue(r.kind()),
                r.targetTitle(), VerificationDto.fromValue(r.verification().name()), evidence(r.evidence())).targetId(r.targetId())).toList())
            .summary(d.summary() == null ? null : new AiSummaryDto(
                d.summary().claims().stream().map(c -> new SummaryClaimDto(c.text(), evidence(c.evidence()))).toList(),
                d.summary().model(), d.summary().promptVersion(), CardMapper.odt(d.summary().generatedAt())))
            .sourceRecords(d.sourceRecords().stream().map(s -> new SourceRecordInfoDto(s.sourceId(), s.externalId(), s.url(),
                CardMapper.odt(s.retrievedAt()), s.fingerprint())).toList())
            .enrichmentStatus(ResourceDetailDto.EnrichmentStatusEnum.fromValue(d.enrichmentStatus()));
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(dto);
    }

    @Override
    public ResponseEntity<RecentResponseDto> listRecent(Integer days, @Nullable List<ResourceTypeDto> type, Integer size) {
        Instant now = clock.instant();
        Instant since = now.minus(Duration.ofDays(days == null ? 14 : days));
        Set<String> types = type == null ? Set.of() : type.stream().map(ResourceTypeDto::getValue).collect(Collectors.toSet());
        List<ResultCardDto> items = recent.list(since, now, types, size == null ? 12 : size).stream().map(CardMapper::card).toList();
        // Short cache: the index changes at most every Scout run (15 min); the page asks on every visit.
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(2)).cachePublic())
            .body(new RecentResponseDto(items, CardMapper.odt(since), CardMapper.odt(now)));
    }

    @Override
    public ResponseEntity<UpcomingEventsResponseDto> listUpcomingEvents(Integer size) {
        Instant now = clock.instant();
        // Start of today (UTC): an event on today's date is still "next" until the day is over.
        Instant today = now.truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        List<ResultCardDto> items = upcoming.list(today, size == null ? 6 : size).stream().map(CardMapper::card).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
            .body(new UpcomingEventsResponseDto(items, CardMapper.odt(now)));
    }

    @Override
    public ResponseEntity<IssueReportAcceptedDto> reportResource(UUID id, IssueReportRequestDto body) {
        String message = body.getMessage().strip();
        if (message.length() < 5) {
            throw new BadRequestException("message too short");
        }
        UUID reportId = reports.submit(id, ReportIssue.Kind.valueOf(body.getKind().getValue()),
                body.getField() == null || body.getField().isBlank() ? null : body.getField().strip(), message,
                currentUser.id().orElse(null))
            .orElseThrow(() -> new NotFoundException("Resource not found in the Helper2 index."));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new IssueReportAcceptedDto(reportId));
    }

    private static List<EvidenceDto> evidence(List<ResourceDetail.Evidence> list) {
        return list.stream().map(e -> new EvidenceDto(e.id(), e.sourceUrl(), CardMapper.odt(e.retrievedAt())).excerpt(e.excerpt())).toList();
    }
}
