package ai.helper2.catalog.web;

import ai.helper2.api.common.model.ValueOriginDto;
import ai.helper2.api.common.model.VerificationDto;
import ai.helper2.api.generated.ResourceDetailApi;
import ai.helper2.api.generated.model.AiSummaryDto;
import ai.helper2.api.generated.model.AssertionDto;
import ai.helper2.api.generated.model.EvidenceDto;
import ai.helper2.api.generated.model.RelationshipDto;
import ai.helper2.api.generated.model.ResourceDetailDto;
import ai.helper2.api.generated.model.SourceRecordInfoDto;
import ai.helper2.api.generated.model.SummaryClaimDto;
import ai.helper2.catalog.ResourceDetail;
import ai.helper2.catalog.usecase.GetResourceDetail;
import ai.helper2.platform.NotFoundException;
import ai.helper2.search.web.CardMapper;
import ai.helper2.shared.UtcTimes;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class ResourceDetailController implements ResourceDetailApi {

  private final GetResourceDetail details;

  ResourceDetailController(GetResourceDetail details) {
    this.details = details;
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

  private static List<EvidenceDto> evidence(List<ResourceDetail.Evidence> list) {
    return list.stream()
        .map(e ->
            new EvidenceDto(e.id(), e.sourceUrl(), UtcTimes.odt(e.retrievedAt()))
                .excerpt(e.excerpt()))
        .toList();
  }
}
