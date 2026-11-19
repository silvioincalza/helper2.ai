package ai.helper2.catalog.web;

import ai.helper2.api.AiContentMarking;
import ai.helper2.api.common.model.AiContentMarkerDto;
import ai.helper2.api.common.model.ValueOriginDto;
import ai.helper2.api.common.model.VerificationDto;
import ai.helper2.api.generated.ResourceDetailApi;
import ai.helper2.api.generated.model.AiSummaryDto;
import ai.helper2.api.generated.model.AssertionDto;
import ai.helper2.api.generated.model.EvidenceDto;
import ai.helper2.api.generated.model.FoundInDto;
import ai.helper2.api.generated.model.FoundInListDto;
import ai.helper2.api.generated.model.RelationshipDto;
import ai.helper2.api.generated.model.ResourceDetailDto;
import ai.helper2.api.generated.model.SourceRecordInfoDto;
import ai.helper2.api.generated.model.SummaryClaimDto;
import ai.helper2.catalog.ResourceDetail;
import ai.helper2.catalog.usecase.GetResourceDetail;
import ai.helper2.platform.NotFoundException;
import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.search.web.CardMapper;
import ai.helper2.shared.UtcTimes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class ResourceDetailController implements ResourceDetailApi {

  static final String ASSERTION_VALUE = "value";
  static final List<String> RELATIONSHIP_FIELDS = List.of("kind", "targetTitle");
  static final String SUMMARY_CLAIMS = "summary.claims[].text";

  static final List<String> AI_WRITTEN_FIELDS =
      Stream.of(
              CardMapper.AI_WRITTEN_FIELDS.stream().map(field -> "card." + field),
              Stream.of("assertions[]." + ASSERTION_VALUE),
              RELATIONSHIP_FIELDS.stream().map(field -> "relationships[]." + field),
              Stream.of(SUMMARY_CLAIMS))
          .flatMap(fields -> fields)
          .toList();

  private final GetResourceDetail details;
  private final String provider;

  ResourceDetailController(GetResourceDetail details, LlmSettings llm) {
    this.details = details;
    this.provider = llm.provider();
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
                                s.fingerprint())
                            .repositoryUrl(s.repositoryUrl()))
                    .toList())
            .enrichmentStatus(
                ResourceDetailDto.EnrichmentStatusEnum.fromValue(d.enrichmentStatus()))
            .foundIn(foundIn(d.foundIn()))
            .aiContent(aiContent(d, provider));
    return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(dto);
  }

  static @Nullable AiContentMarkerDto aiContent(ResourceDetail d, String provider) {
    List<String> fields = new ArrayList<>();
    List<@Nullable String> models = new ArrayList<>();
    CardMapper.aiWrittenFields(d.card()).forEach(field -> fields.add("card." + field));
    fields.addAll(
        AiContentMarking.inEachItem(
            "assertions",
            d.assertions().stream()
                .map(a -> a.origin().isFromAModel() ? List.of(ASSERTION_VALUE) : List.<String>of())
                .toList()));
    fields.addAll(
        AiContentMarking.inEachItem(
            "relationships",
            d.relationships().stream()
                .map(r -> r.origin().isFromAModel() ? RELATIONSHIP_FIELDS : List.<String>of())
                .toList()));
    d.assertions().stream()
        .filter(a -> a.origin().isFromAModel())
        .forEach(a -> models.add(a.model()));
    d.relationships().stream()
        .filter(r -> r.origin().isFromAModel())
        .forEach(r -> models.add(r.model()));
    if (d.summary() != null && !d.summary().claims().isEmpty()) {
      fields.add(SUMMARY_CLAIMS);
      models.add(d.summary().model());
    }
    return AiContentMarking.marker(provider, models, fields);
  }

  private static FoundInDto foundIn(ResourceDetail.FoundIn found) {
    return new FoundInDto(
        found.lists().stream()
            .map(l -> new FoundInListDto(l.listId(), l.listTitle(), l.headingPath()))
            .toList(),
        found.more());
  }

  private static List<EvidenceDto> evidence(List<ResourceDetail.Evidence> list) {
    return list.stream()
        .map(e ->
            new EvidenceDto(e.id(), e.sourceUrl(), UtcTimes.odt(e.retrievedAt()))
                .excerpt(e.excerpt()))
        .toList();
  }
}
