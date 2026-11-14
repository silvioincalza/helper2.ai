package ai.helper2.catalog.web;

import ai.helper2.api.PublicCache;
import ai.helper2.api.common.model.ResourceTypeDto;
import ai.helper2.api.common.model.ResultCardDto;
import ai.helper2.api.common.model.ValueOriginDto;
import ai.helper2.api.common.model.VerificationDto;
import ai.helper2.api.generated.ResourcesApi;
import ai.helper2.api.generated.model.AiSummaryDto;
import ai.helper2.api.generated.model.AssertionDto;
import ai.helper2.api.generated.model.EvidenceDto;
import ai.helper2.api.generated.model.IssueReportAcceptedDto;
import ai.helper2.api.generated.model.IssueReportRequestDto;
import ai.helper2.api.generated.model.RecentResponseDto;
import ai.helper2.api.generated.model.RelationshipDto;
import ai.helper2.api.generated.model.ResourceDetailDto;
import ai.helper2.api.generated.model.SourceRecordInfoDto;
import ai.helper2.api.generated.model.SummaryClaimDto;
import ai.helper2.api.generated.model.UpcomingEventsResponseDto;
import ai.helper2.catalog.ResourceDetail;
import ai.helper2.catalog.usecase.GetResourceDetail;
import ai.helper2.catalog.usecase.ListRecentResources;
import ai.helper2.catalog.usecase.ListUpcomingEvents;
import ai.helper2.catalog.usecase.ReportIssue;
import ai.helper2.platform.BadRequestException;
import ai.helper2.platform.NotFoundException;
import ai.helper2.search.web.CardMapper;
import ai.helper2.shared.UtcTimes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

  private static final CacheControl RECENT_CACHE =
      CacheControl.maxAge(Duration.ofMinutes(2)).cachePublic();

  private final GetResourceDetail details;
  private final ListRecentResources recent;
  private final ListUpcomingEvents upcoming;
  private final ReportIssue reports;
  private final Clock clock;

  ResourcesController(
      GetResourceDetail details,
      ListRecentResources recent,
      ListUpcomingEvents upcoming,
      ReportIssue reports,
      Clock clock) {
    this.details = details;
    this.recent = recent;
    this.upcoming = upcoming;
    this.clock = clock;
    this.reports = reports;
  }

  @Override
  public ResponseEntity<ResourceDetailDto> getResource(UUID id) {
    ResourceDetail d =
        details
            .find(id)
            .orElseThrow(
                () -> new NotFoundException("Resource not found in the helper2.ai catalog."));
    ResourceDetailDto dto =
        new ResourceDetailDto()
            .card(CardMapper.card(d.card()))
            .assertions(
                d.assertions().stream()
                    .map(a ->
                        new AssertionDto(
                                a.field(),
                                a.value(),
                                ValueOriginDto.fromValue(a.origin().name()),
                                VerificationDto.fromValue(a.verification().name()),
                                evidence(a.evidence()))
                            .sourceId(a.sourceId()))
                    .toList())
            .relationships(
                d.relationships().stream()
                    .map(r ->
                        new RelationshipDto(
                                RelationshipDto.KindEnum.fromValue(r.kind()),
                                r.targetTitle(),
                                VerificationDto.fromValue(r.verification().name()),
                                evidence(r.evidence()))
                            .targetId(r.targetId())
                            .origin(ValueOriginDto.fromValue(r.origin().name())))
                    .toList())
            .summary(
                d.summary() == null
                    ? null
                    : new AiSummaryDto(
                        d.summary().claims().stream()
                            .map(c ->
                                new SummaryClaimDto(c.text(), evidence(c.evidence()))
                                    .selfDeclared(c.selfDeclared()))
                            .toList(),
                        d.summary().model(),
                        d.summary().promptVersion(),
                        UtcTimes.odt(d.summary().generatedAt())))
            .sourceRecords(
                d.sourceRecords().stream()
                    .map(s ->
                        new SourceRecordInfoDto(
                            s.sourceId(),
                            s.externalId(),
                            s.url(),
                            UtcTimes.odt(s.retrievedAt()),
                            s.fingerprint()))
                    .toList())
            .enrichmentStatus(
                ResourceDetailDto.EnrichmentStatusEnum.fromValue(d.enrichmentStatus()));
    return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(dto);
  }

  @Override
  public ResponseEntity<RecentResponseDto> listRecent(
      Integer days, @Nullable List<ResourceTypeDto> type, Integer size) {
    Instant now = clock.instant();
    Instant since = now.minus(Duration.ofDays(days == null ? 14 : days));
    Set<String> types =
        type == null
            ? Set.of()
            : type.stream().map(ResourceTypeDto::getValue).collect(Collectors.toSet());
    ListRecentResources.Page page = recent.list(since, now, types, size == null ? 12 : size);
    List<ResultCardDto> items = page.items().stream().map(CardMapper::card).toList();
    return ResponseEntity.ok()
        .cacheControl(RECENT_CACHE)
        .body(new RecentResponseDto(items, page.total(), UtcTimes.odt(since), UtcTimes.odt(now)));
  }

  @Override
  public ResponseEntity<UpcomingEventsResponseDto> listUpcomingEvents(
      @Nullable Integer days, Integer size) {
    Instant now = clock.instant();
    Instant today = now.truncatedTo(ChronoUnit.DAYS);
    Instant until = days == null ? null : today.plus(Duration.ofDays(days));
    ListUpcomingEvents.Page page = upcoming.list(today, until, size == null ? 6 : size);
    List<ResultCardDto> items = page.items().stream().map(CardMapper::card).toList();
    return ResponseEntity.ok()
        .cacheControl(PublicCache.SHORT)
        .body(
            new UpcomingEventsResponseDto(items, page.total(), UtcTimes.odt(now))
                .until(UtcTimes.odt(until)));
  }

  @Override
  public ResponseEntity<IssueReportAcceptedDto> reportResource(
      UUID id, IssueReportRequestDto body) {
    String message = body.getMessage().strip();
    if (message.length() < 5) {
      throw new BadRequestException("message too short");
    }
    UUID reportId =
        reports
            .submit(
                id,
                ReportIssue.Kind.valueOf(body.getKind().getValue()),
                body.getField() == null || body.getField().isBlank()
                    ? null
                    : body.getField().strip(),
                message)
            .orElseThrow(
                () -> new NotFoundException("Resource not found in the helper2.ai catalog."));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(new IssueReportAcceptedDto(reportId));
  }

  private static List<EvidenceDto> evidence(List<ResourceDetail.Evidence> list) {
    return list.stream()
        .map(e ->
            new EvidenceDto(e.id(), e.sourceUrl(), UtcTimes.odt(e.retrievedAt()))
                .excerpt(e.excerpt()))
        .toList();
  }
}
