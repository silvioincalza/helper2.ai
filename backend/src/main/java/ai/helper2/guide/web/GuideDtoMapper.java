package ai.helper2.guide.web;

import ai.helper2.api.generated.model.GuideCheckDto;
import ai.helper2.api.generated.model.GuideModelInfoDto;
import ai.helper2.api.generated.model.GuidePromptBasedOnInnerDto;
import ai.helper2.api.generated.model.GuidePromptDto;
import ai.helper2.api.generated.model.GuideRecommendationDto;
import ai.helper2.api.generated.model.GuideResponseDto;
import ai.helper2.api.generated.model.NoticeDto;
import ai.helper2.api.generated.model.ResourceTypeDto;
import ai.helper2.api.generated.model.ServedFromDto;
import ai.helper2.api.generated.model.SourceModeDto;
import ai.helper2.api.generated.model.SourceOutcomeDto;
import ai.helper2.api.generated.model.SourceStatusDto;
import ai.helper2.guide.ConstraintCheck;
import ai.helper2.guide.GuideAnswer;
import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.search.web.CardMapper;
import ai.helper2.search.MergedResult;
import ai.helper2.search.SearchOutcome;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** One mapping from the guide's domain answer to the contract, shared by the JSON and the streamed endpoint. */
@Component
class GuideDtoMapper {

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
            .recommendations(a.getRecommendations().stream().map(r -> new GuideRecommendationDto(
                r.getResult().getId().getValue(), r.getResult().getTitle(), r.getResult().getUrl(),
                ResourceTypeDto.fromValue(r.getResult().getType().name()), r.getReason(), r.getQuote(),
                r.getResult().getSources().stream().map(s -> s.getSourceId()).toList(), r.getAiReasoned())
                .description(r.getResult().getDescription())
                .checks(r.getChecks().stream().map(GuideDtoMapper::check).toList())
                .reasonCheck(GuideRecommendationDto.ReasonCheckEnum.fromValue(r.getReasonCheck().name()))).toList())
            .constraints(a.getConstraints())
            .prompt(a.getPrompt() == null ? null : new GuidePromptDto(a.getPrompt().getText(),
                a.getPrompt().getBasedOn().stream().map(GuideDtoMapper::basedOn).toList()))
            .model(a.getModelUsed() ? new GuideModelInfoDto(provider, fastModel, reasoningModel) : null)
            .candidatesConsidered(a.getCandidatesConsidered())
            .rejected(a.getRejected())
            .sources(statuses(a.getSearch()))
            .notices(Stream.concat(a.getSearch().getNotices().stream(), a.getNotices().stream())
                .map(n -> new NoticeDto(NoticeDto.CodeEnum.fromValue(n.getCode().name()), n.getMessage())).toList())
            .generatedAt(CardMapper.odt(a.getGeneratedAt()));
    }

    static List<SourceStatusDto> statuses(SearchOutcome outcome) {
        return outcome.getStatuses().stream().map(s -> new SourceStatusDto(s.getSource().getId(),
                s.getSource().getName(), SourceModeDto.fromValue(s.getSource().getMode().name()),
                SourceOutcomeDto.fromValue(s.getOutcome().name()), ServedFromDto.fromValue(s.getServedFrom().name()),
                s.getResultCount(), CardMapper.odt(s.getCheckedAt()))
            .latencyMs(s.getLatency() == null ? null : s.getLatency().toMillis())
            .cachedAt(CardMapper.odt(s.getCachedAt())).message(s.getMessage())).toList();
    }

    private static GuideCheckDto check(ConstraintCheck c) {
        return new GuideCheckDto(c.getConstraint(), GuideCheckDto.StatusEnum.fromValue(c.getStatus().name())).quote(c.getQuote());
    }

    private static GuidePromptBasedOnInnerDto basedOn(MergedResult r) {
        return new GuidePromptBasedOnInnerDto(r.getId().getValue(), r.getTitle());
    }
}
