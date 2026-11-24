package ai.searchavo.guide.web;

import ai.searchavo.api.AiContentMarking;
import ai.searchavo.api.common.model.AiContentMarkerDto;
import ai.searchavo.api.common.model.NoticeDto;
import ai.searchavo.api.common.model.ResourceTypeDto;
import ai.searchavo.api.common.model.ServedFromDto;
import ai.searchavo.api.common.model.SkipReasonDto;
import ai.searchavo.api.common.model.SourceModeDto;
import ai.searchavo.api.common.model.SourceOutcomeDto;
import ai.searchavo.api.common.model.SourceStatusDto;
import ai.searchavo.api.generated.model.GuideCheckDto;
import ai.searchavo.api.generated.model.GuideClarificationDto;
import ai.searchavo.api.generated.model.GuideComparisonDto;
import ai.searchavo.api.generated.model.GuideComparisonRowsInnerCellsInnerDto;
import ai.searchavo.api.generated.model.GuideComparisonRowsInnerDto;
import ai.searchavo.api.generated.model.GuideComparisonSubjectsInnerDto;
import ai.searchavo.api.generated.model.GuideExplanationDto;
import ai.searchavo.api.generated.model.GuideExplanationSentencesInnerDto;
import ai.searchavo.api.generated.model.GuideIntentDto;
import ai.searchavo.api.generated.model.GuideModelInfoDto;
import ai.searchavo.api.generated.model.GuideRecommendationDto;
import ai.searchavo.api.generated.model.GuideResponseDto;
import ai.searchavo.api.generated.model.GuideTrailDto;
import ai.searchavo.api.generated.model.GuideTrailRemovedDto;
import ai.searchavo.api.generated.model.GuideWindowDto;
import ai.searchavo.api.generated.model.QuotedFromDto;
import ai.searchavo.guide.Clarification;
import ai.searchavo.guide.Comparison;
import ai.searchavo.guide.ConstraintCheck;
import ai.searchavo.guide.DateWindow;
import ai.searchavo.guide.Explanation;
import ai.searchavo.guide.GuideAnswer;
import ai.searchavo.guide.GuideKt;
import ai.searchavo.guide.QuotedFrom;
import ai.searchavo.guide.ResearchTrail;
import ai.searchavo.platform.llm.LlmSettings;
import ai.searchavo.search.Notice;
import ai.searchavo.search.NoticeCode;
import ai.searchavo.search.SearchOutcome;
import ai.searchavo.search.web.CardMapper;
import ai.searchavo.shared.UtcTimes;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
class GuideDtoMapper {
  static final List<String> AI_WRITTEN_FIELDS =
      List.of(
          "searchQuery",
          "constraints[]",
          "recommendations[].reason",
          "recommendations[].checks[].constraint",
          "clarification.question",
          "clarification.options[]",
          "comparison.rows[].aspect",
          "comparison.rows[].cells[].text",
          "explanation.sentences[].text",
          "trail.queries[]",
          "notices[].subject",
          "notices[].query");

  static final String AI_MARKING_POLICY = AiContentMarking.POLICY;

  static final List<String> CLARIFY_FIELDS = List.of("question", "options[]");

  private final String provider;
  private final String fastModel;
  private final String reasoningModel;

  GuideDtoMapper(LlmSettings llm) {
    this.provider = llm.provider();
    this.fastModel = llm.fast();
    this.reasoningModel = llm.reasoningModel();
  }

  String fastModel() {
    return fastModel;
  }

  String reasoningModel() {
    return reasoningModel;
  }

  GuideResponseDto toDto(GuideAnswer a) {
    return new GuideResponseDto()
        .status(GuideResponseDto.StatusEnum.fromValue(a.getStatus().name()))
        .searchQuery(a.getSearchQuery())
        .recommendations(
            a.getRecommendations().stream()
                .map(r ->
                    new GuideRecommendationDto(
                            r.getResult().getId().getValue(),
                            r.getResult().getTitle(),
                            r.getResult().getUrl(),
                            ResourceTypeDto.fromValue(r.getResult().getType().name()),
                            r.getReason(),
                            r.getQuote(),
                            r.getResult().getSources().stream().map(s -> s.getSourceId()).toList(),
                            r.getAiReasoned())
                        .description(GuideKt.getOwnDescription(r.getResult()))
                        .quotedFrom(quotedFrom(r.getQuotedFrom()))
                        .compatibleWith(CardMapper.compatibility(r.getResult()))
                        .checks(r.getChecks().stream().map(GuideDtoMapper::check).toList())
                        .reasonCheck(
                            GuideRecommendationDto.ReasonCheckEnum.fromValue(
                                r.getReasonCheck().name())))
                .toList())
        .constraints(a.getConstraints())
        .model(a.getModelUsed() ? new GuideModelInfoDto(provider, fastModel, reasoningModel) : null)
        .aiContent(aiContent(a))
        .candidatesConsidered(a.getCandidatesConsidered())
        .rejected(a.getRejected())
        .sources(statuses(a.getSearch()))
        .notices(notices(a))
        .generatedAt(UtcTimes.odt(a.getGeneratedAt()))
        .intent(GuideIntentDto.fromValue(a.getIntent().name()))
        .subjects(a.getSubjects())
        .window(window(a.getWindow()))
        .clarification(clarification(a.getClarification()))
        .comparison(comparison(a.getComparison()))
        .explanation(explanation(a.getExplanation()))
        .webSearches(CardMapper.webSearches(a.getWebSearches()))
        .trail(trail(a.getTrail()));
  }

  static GuideTrailDto trail(ResearchTrail t) {
    return new GuideTrailDto(
        t.getQueries(),
        t.getRemoved().stream()
            .map(r ->
                new GuideTrailRemovedDto(
                    r.getTitle(), GuideTrailRemovedDto.CheckEnum.fromValue(r.getCheck().name())))
            .toList());
  }

  private AiContentMarkerDto aiContent(GuideAnswer a) {
    List<String> fields = aiWrittenFields(a);
    if (fields.isEmpty()) {
      return null;
    }
    List<String> models =
        a.getModelUsed()
            ? Stream.of(fastModel, reasoningModel).distinct().toList()
            : List.of(fastModel);
    return marker(models, fields);
  }

  AiContentMarkerDto plannedMarker(String searchQuery, List<String> constraints) {
    List<String> fields =
        Stream.of(
                searchQuery.isBlank() ? null : "searchQuery",
                constraints.isEmpty() ? null : "constraints[]")
            .filter(Objects::nonNull)
            .toList();
    return fields.isEmpty() ? null : marker(List.of(fastModel), fields);
  }

  AiContentMarkerDto clarifyMarker(Clarification c) {
    return c.getAiWritten() ? marker(List.of(fastModel), CLARIFY_FIELDS) : null;
  }

  private AiContentMarkerDto marker(List<String> models, List<String> fields) {
    return new AiContentMarkerDto(true, provider, models, fields, AI_MARKING_POLICY);
  }

  static List<String> aiWrittenFields(GuideAnswer a) {
    Set<String> held = new HashSet<>();
    if (a.getPlanned()) {
      if (!a.getSearchQuery().isBlank()) {
        held.add("searchQuery");
      }
      if (!a.getTrail().getQueries().isEmpty()) {
        held.add("trail.queries[]");
      }
      if (!a.getConstraints().isEmpty()) {
        held.add("constraints[]");
        held.add("recommendations[].checks[].constraint");
      }
    }
    if (a.getRecommendations().stream()
        .anyMatch(r -> r.getAiReasoned() && !r.getReason().isBlank())) {
      held.add("recommendations[].reason");
    }
    if (a.getClarification() != null && a.getClarification().getAiWritten()) {
      held.add("clarification.question");
      held.add("clarification.options[]");
    }
    if (a.getComparison() != null && !a.getComparison().getRows().isEmpty()) {
      held.add("comparison.rows[].aspect");
      held.add("comparison.rows[].cells[].text");
    }
    if (a.getExplanation() != null && !a.getExplanation().getSentences().isEmpty()) {
      held.add("explanation.sentences[].text");
    }
    List<Notice> notices =
        Stream.concat(a.getSearch().getNotices().stream(), a.getNotices().stream()).toList();
    if (notices.stream().anyMatch(n -> n.getSubject() != null)) {
      held.add("notices[].subject");
    }
    if (notices.stream().anyMatch(n -> n.getQuery() != null)) {
      held.add("notices[].query");
    }
    return AI_WRITTEN_FIELDS.stream().filter(held::contains).toList();
  }

  private static List<NoticeDto> notices(GuideAnswer a) {
    return Stream.concat(
            a.getSearch().getNotices().stream()
                .filter(n -> n.getCode() != NoticeCode.QUERY_EXPANDED),
            a.getNotices().stream())
        .map(GuideDtoMapper::notice)
        .toList();
  }

  static GuideWindowDto window(DateWindow w) {
    return w == null ? null : new GuideWindowDto(w.getFrom(), w.getTo());
  }

  static QuotedFromDto quotedFrom(QuotedFrom q) {
    return q == null ? null : new QuotedFromDto(q.getSourceId(), q.getUrl());
  }

  static GuideClarificationDto clarification(Clarification c) {
    return c == null ? null : new GuideClarificationDto(c.getQuestion(), c.getOptions());
  }

  private static GuideComparisonDto comparison(Comparison c) {
    if (c == null) {
      return null;
    }
    return new GuideComparisonDto(
        c.getSubjects().stream()
            .map(s ->
                new GuideComparisonSubjectsInnerDto(s.getKey(), s.getName())
                    .resourceId(s.getResult() == null ? null : s.getResult().getId().getValue())
                    .title(s.getResult() == null ? null : s.getResult().getTitle())
                    .url(s.getResult() == null ? null : s.getResult().getUrl()))
            .toList(),
        c.getRows().stream()
            .map(r ->
                new GuideComparisonRowsInnerDto(
                    r.getAspect(),
                    r.getCells().stream()
                        .map(cell ->
                            new GuideComparisonRowsInnerCellsInnerDto(cell.getSubjectKey())
                                .text(cell.getText())
                                .quote(cell.getQuote())
                                .quotedFrom(quotedFrom(cell.getQuotedFrom()))
                                .reasonCheck(
                                    GuideComparisonRowsInnerCellsInnerDto.ReasonCheckEnum.fromValue(
                                        cell.getReasonCheck().name())))
                        .toList()))
            .toList());
  }

  private static GuideExplanationDto explanation(Explanation e) {
    if (e == null) {
      return null;
    }
    return new GuideExplanationDto(
        e.getSentences().stream()
            .map(x ->
                new GuideExplanationSentencesInnerDto(
                        x.getText(),
                        x.getQuote(),
                        x.getSource().getId().getValue(),
                        x.getSource().getTitle(),
                        x.getSource().getUrl())
                    .quotedFrom(quotedFrom(x.getQuotedFrom()))
                    .reasonCheck(
                        GuideExplanationSentencesInnerDto.ReasonCheckEnum.fromValue(
                            x.getReasonCheck().name())))
            .toList());
  }

  static List<SourceStatusDto> statuses(SearchOutcome outcome) {
    return outcome.getStatuses().stream()
        .map(s ->
            new SourceStatusDto(
                    s.getSource().getId(),
                    s.getSource().getName(),
                    SourceModeDto.fromValue(s.getSource().getMode().name()),
                    SourceOutcomeDto.fromValue(s.getOutcome().name()),
                    ServedFromDto.fromValue(s.getServedFrom().name()),
                    s.getResultCount(),
                    UtcTimes.odt(s.getCheckedAt()))
                .latencyMs(s.getLatency() == null ? null : s.getLatency().toMillis())
                .cachedAt(UtcTimes.odt(s.getCachedAt()))
                .message(s.getMessage())
                .skipReason(
                    s.getSkipReason() == null
                        ? null
                        : SkipReasonDto.fromValue(s.getSkipReason().name())))
        .toList();
  }

  static NoticeDto notice(Notice n) {
    return new NoticeDto(NoticeDto.CodeEnum.fromValue(n.getCode().name()), n.getMessage())
        .subject(n.getSubject())
        .query(n.getQuery())
        .retryAt(UtcTimes.odt(n.getRetryAt()));
  }

  private static GuideCheckDto check(ConstraintCheck c) {
    return new GuideCheckDto(
            c.getConstraint(), GuideCheckDto.StatusEnum.fromValue(c.getStatus().name()))
        .quote(c.getQuote());
  }
}
