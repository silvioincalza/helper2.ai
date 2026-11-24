package ai.helper2.search.web;

import ai.helper2.api.common.model.AudienceDto;
import ai.helper2.api.common.model.CompatibilityFlagDto;
import ai.helper2.api.common.model.DateFactDto;
import ai.helper2.api.common.model.EventFormatDto;
import ai.helper2.api.common.model.EventKindDto;
import ai.helper2.api.common.model.FieldConflictDto;
import ai.helper2.api.common.model.FreshnessDto;
import ai.helper2.api.common.model.ListMatchDto;
import ai.helper2.api.common.model.PopularityMetricDto;
import ai.helper2.api.common.model.PricingDto;
import ai.helper2.api.common.model.ResourceTypeDto;
import ai.helper2.api.common.model.ResultCardDto;
import ai.helper2.api.common.model.ResultSourceRefDto;
import ai.helper2.api.common.model.SourceModeDto;
import ai.helper2.api.common.model.SourcedValueDto;
import ai.helper2.api.common.model.TechLevelDto;
import ai.helper2.api.common.model.TimelineDto;
import ai.helper2.api.common.model.ValueOriginDto;
import ai.helper2.api.common.model.WebSearchesDto;
import ai.helper2.search.Compatibility;
import ai.helper2.search.MergedResult;
import ai.helper2.search.WebSearchesLeft;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.UtcTimes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.modulith.NamedInterface;

@NamedInterface("cards")
public final class CardMapper {

  public static final List<String> AI_WRITTEN_FIELDS =
      List.of(
          "description",
          "timeline.eventStart.value",
          "timeline.eventEnd.value",
          "eventKind",
          "eventFormat",
          "location");

  private CardMapper() {}

  // UC-001 BR-015: an AI-written description in a result is marked.
  public static List<String> aiWrittenFields(MergedResult r) {
    ResultCardDto card = card(r);
    List<String> fields = new ArrayList<>();
    if (card.getDescription() != null && r.getDescriptionOrigin().isFromAModel()) {
      fields.add("description");
    }
    if (card.getEventOrigin() == ValueOriginDto.AI_EXTRACTED) {
      fields.addAll(eventFieldsHeld(card));
    }
    return fields;
  }

  private static List<String> eventFieldsHeld(ResultCardDto card) {
    List<String> held = new ArrayList<>();
    if (card.getTimeline().getEventStart().getValue() != null) {
      held.add("timeline.eventStart.value");
    }
    if (card.getTimeline().getEventEnd() != null
        && card.getTimeline().getEventEnd().getValue() != null) {
      held.add("timeline.eventEnd.value");
    }
    if (card.getEventKind() != null) {
      held.add("eventKind");
    }
    if (card.getEventFormat() != null) {
      held.add("eventFormat");
    }
    if (card.getLocation() != null) {
      held.add("location");
    }
    return held;
  }

  public static ResultCardDto card(MergedResult r) {
    ResultCardDto dto =
        new ResultCardDto()
            .id(r.getId().getValue())
            .title(r.getTitle())
            .description(r.getDescription())
            .descriptionOrigin(ValueOriginDto.fromValue(r.getDescriptionOrigin().name()))
            .type(ResourceTypeDto.fromValue(r.getType().name()))
            .audience(AudienceDto.fromValue(r.getAudience().name()))
            .techLevel(
                r.getTechLevel() == null ? null : TechLevelDto.fromValue(r.getTechLevel().name()))
            .useCases(new ArrayList<>(r.getUseCases()))
            .tags(new ArrayList<>(r.getTags()))
            .categories(new ArrayList<>(r.getCategories()))
            .license(r.getLicense())
            .pricing(r.getPricing() == null ? null : PricingDto.fromValue(r.getPricing().name()))
            .platforms(new ArrayList<>(r.getPlatforms()))
            .openSource(r.getOpenSource())
            .url(r.getUrl())
            .canonicalUrl(r.getCanonicalUrl().getValue())
            .timeline(
                new TimelineDto(
                        date(r.getPublished()), date(r.getUpdated()), date(r.getDiscovered()))
                    .eventStart(
                        r.getType() == ai.helper2.shared.ResourceType.EVENT
                            ? date(r.getEventStart())
                            : null)
                    .eventEnd(
                        r.getType() == ai.helper2.shared.ResourceType.EVENT
                            ? date(r.getEventEnd())
                            : null))
            .location(r.getType() == ai.helper2.shared.ResourceType.EVENT ? r.getLocation() : null)
            .eventKind(
                r.getType() == ai.helper2.shared.ResourceType.EVENT && r.getEventKind() != null
                    ? EventKindDto.fromValue(r.getEventKind().name())
                    : null)
            .eventFormat(
                r.getType() == ai.helper2.shared.ResourceType.EVENT && r.getEventFormat() != null
                    ? EventFormatDto.fromValue(r.getEventFormat().name())
                    : null)
            .eventOrigin(eventOrigin(r))
            .freshness(FreshnessDto.fromValue(r.getFreshness().name()));
    r.getSources()
        .forEach(s ->
            dto.addSourcesItem(
                new ResultSourceRefDto(
                    s.getSourceId(),
                    s.getSourceName(),
                    s.getUrl(),
                    SourceModeDto.fromValue(s.getMode().name()),
                    UtcTimes.odt(s.getRetrievedAt()))));
    r.getPopularity()
        .forEach(p ->
            dto.addPopularityItem(
                new PopularityMetricDto(
                    PopularityMetricDto.MetricEnum.fromValue(p.getKind().name()),
                    p.getValue(),
                    p.getSourceId(),
                    UtcTimes.odt(p.getAsOf()))));
    r.getConflicts()
        .forEach(c ->
            dto.addConflictsItem(
                new FieldConflictDto(
                    c.getField(),
                    c.getValues().stream()
                        .map(v -> new SourcedValueDto(v.getValue(), v.getSourceId()))
                        .toList())));
    dto.compatibleWith(compatibility(r));
    if (r.getFoundIn() != null) {
      dto.foundIn(new ListMatchDto(r.getFoundIn().getListTitle(), r.getFoundIn().getHeadingPath()));
    }
    return dto;
  }

  public static List<CompatibilityFlagDto> compatibility(MergedResult r) {
    return Compatibility.INSTANCE.of(r).stream()
        .map(f ->
            new CompatibilityFlagDto(
                    f.getTool(),
                    f.getLabel(),
                    CompatibilityFlagDto.BasisEnum.fromValue(f.getBasis().name()),
                    f.getEvidence())
                .sourceId(f.getSourceId()))
        .collect(Collectors.toCollection(ArrayList::new));
  }

  public static DateFactDto date(DateFact f) {
    DateFactDto dto =
        new DateFactDto(
                DateFactDto.PrecisionEnum.fromValue(f.getPrecision().name()),
                DateFactDto.StatusEnum.fromValue(f.getStatus().name()))
            .value(UtcTimes.odt(f.getValue()))
            .sourceId(f.getValue() == null ? null : f.getSourceId());
    f.getAlternatives()
        .forEach(a -> dto.addAlternativesItem(new SourcedValueDto(a.getValue(), a.getSourceId())));
    return dto;
  }

  public static WebSearchesDto webSearches(WebSearchesLeft left) {
    return left == null ? null : new WebSearchesDto(left.getLimit(), left.getRemaining());
  }

  static ValueOriginDto eventOrigin(MergedResult r) {
    if (r.getType() != ai.helper2.shared.ResourceType.EVENT
        || r.getEventStart().getStatus() == ai.helper2.shared.DateStatus.UNKNOWN) {
      return null;
    }
    return ai.helper2.shared.WebSources.eventDaysReadByAi(r.getEventStart().getSourceId())
        ? ValueOriginDto.AI_EXTRACTED
        : ValueOriginDto.SOURCE_PROVIDED;
  }
}
