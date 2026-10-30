package ai.helper2.search.web;

import ai.helper2.api.generated.model.AudienceDto;
import ai.helper2.api.generated.model.CompatibilityFlagDto;
import ai.helper2.api.generated.model.DateFactDto;
import ai.helper2.api.generated.model.EventFormatDto;
import ai.helper2.api.generated.model.EventKindDto;
import ai.helper2.api.generated.model.FieldConflictDto;
import ai.helper2.api.generated.model.FreshnessDto;
import ai.helper2.api.generated.model.PopularityMetricDto;
import ai.helper2.api.generated.model.PricingDto;
import ai.helper2.api.generated.model.ResourceTypeDto;
import ai.helper2.api.generated.model.ResultCardDto;
import ai.helper2.api.generated.model.ResultSourceRefDto;
import ai.helper2.api.generated.model.SourceModeDto;
import ai.helper2.api.generated.model.SourcedValueDto;
import ai.helper2.api.generated.model.TechLevelDto;
import ai.helper2.api.generated.model.TimelineDto;
import ai.helper2.api.generated.model.ValueOriginDto;
import ai.helper2.search.Compatibility;
import ai.helper2.search.MergedResult;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.UtcTimes;
import java.util.ArrayList;
import org.springframework.modulith.NamedInterface;

/**
 * Maps search-domain results to contract DTOs: the search module's "cards" named interface. Result cards
 * ({@link #card}) are shown by search and catalog; the guide and admin adapters use only {@link #odt}.
 */
@NamedInterface("cards")
public final class CardMapper {

    private CardMapper() {
    }

    public static ResultCardDto card(MergedResult r) {
        ResultCardDto dto = new ResultCardDto()
            .id(r.getId().getValue())
            .title(r.getTitle())
            .description(r.getDescription())
            .descriptionOrigin(ValueOriginDto.fromValue(r.getDescriptionOrigin().name()))
            .type(ResourceTypeDto.fromValue(r.getType().name()))
            .audience(AudienceDto.fromValue(r.getAudience().name()))
            .techLevel(r.getTechLevel() == null ? null : TechLevelDto.fromValue(r.getTechLevel().name()))
            .useCases(new ArrayList<>(r.getUseCases()))
            .tags(new ArrayList<>(r.getTags()))
            .license(r.getLicense())
            .pricing(r.getPricing() == null ? null : PricingDto.fromValue(r.getPricing().name()))
            .platforms(new ArrayList<>(r.getPlatforms()))
            .openSource(r.getOpenSource())
            .url(r.getUrl())
            .canonicalUrl(r.getCanonicalUrl().getValue())
            .timeline(new TimelineDto(date(r.getPublished()), date(r.getUpdated()), date(r.getDiscovered()))
                // Events show the days their source states, or "unknown"; never a guess.
                .eventStart(r.getType() == ai.helper2.shared.ResourceType.EVENT ? date(r.getEventStart()) : null)
                .eventEnd(r.getType() == ai.helper2.shared.ResourceType.EVENT ? date(r.getEventEnd()) : null))
            .location(r.getType() == ai.helper2.shared.ResourceType.EVENT ? r.getLocation() : null)
            .eventKind(r.getType() == ai.helper2.shared.ResourceType.EVENT && r.getEventKind() != null
                ? EventKindDto.fromValue(r.getEventKind().name()) : null)
            .eventFormat(r.getType() == ai.helper2.shared.ResourceType.EVENT && r.getEventFormat() != null
                ? EventFormatDto.fromValue(r.getEventFormat().name()) : null)
            .freshness(FreshnessDto.fromValue(r.getFreshness().name()));
        r.getSources().forEach(s -> dto.addSourcesItem(new ResultSourceRefDto(
            s.getSourceId(), s.getSourceName(), s.getUrl(), SourceModeDto.fromValue(s.getMode().name()), UtcTimes.odt(s.getRetrievedAt()))));
        r.getPopularity().forEach(p -> dto.addPopularityItem(new PopularityMetricDto(
            PopularityMetricDto.MetricEnum.fromValue(p.getKind().name()), p.getValue(), p.getSourceId(), UtcTimes.odt(p.getAsOf()))));
        r.getConflicts().forEach(c -> dto.addConflictsItem(new FieldConflictDto(c.getField(),
            c.getValues().stream().map(v -> new SourcedValueDto(v.getValue(), v.getSourceId())).toList())));
        Compatibility.INSTANCE.of(r).forEach(f -> dto.addCompatibleWithItem(new CompatibilityFlagDto(
            f.getTool(), f.getLabel(), CompatibilityFlagDto.BasisEnum.fromValue(f.getBasis().name()), f.getEvidence())
            .sourceId(f.getSourceId())));
        return dto;
    }

    public static DateFactDto date(DateFact f) {
        DateFactDto dto = new DateFactDto(
            DateFactDto.PrecisionEnum.fromValue(f.getPrecision().name()),
            DateFactDto.StatusEnum.fromValue(f.getStatus().name()))
            .value(UtcTimes.odt(f.getValue()))
            .sourceId(f.getValue() == null ? null : f.getSourceId());
        f.getAlternatives().forEach(a -> dto.addAlternativesItem(new SourcedValueDto(a.getValue(), a.getSourceId())));
        return dto;
    }
}
