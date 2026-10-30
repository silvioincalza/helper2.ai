package ai.helper2.setup.web;

import ai.helper2.api.generated.SetupApi;
import ai.helper2.api.generated.model.SetupClientDto;
import ai.helper2.api.generated.model.SetupClientSnippetsInnerDto;
import ai.helper2.api.generated.model.SetupGuideDto;
import ai.helper2.api.generated.model.SetupGuideSourcesInnerDto;
import ai.helper2.api.generated.model.SetupOptionDto;
import ai.helper2.api.generated.model.SetupSettingDto;
import ai.helper2.api.generated.model.ToolGuideDocumentsInnerDto;
import ai.helper2.api.generated.model.ToolGuideDto;
import ai.helper2.api.generated.model.ToolGuideRequestDto;
import ai.helper2.api.generated.model.ToolGuideSectionsInnerDto;
import ai.helper2.api.generated.model.ToolGuideSectionsInnerStepsInnerCodeDto;
import ai.helper2.api.generated.model.ToolGuideSectionsInnerStepsInnerDto;
import ai.helper2.platform.NotFoundException;
import ai.helper2.setup.ClientGuide;
import ai.helper2.setup.RunOption;
import ai.helper2.setup.Setting;
import ai.helper2.setup.SetupGuide;
import ai.helper2.setup.SetupGuides;
import ai.helper2.setup.SetupUnavailable;
import ai.helper2.setup.ShownOption;
import ai.helper2.setup.GuideStep;
import ai.helper2.setup.ToolGuideAnswer;
import ai.helper2.setup.usecase.AskAboutResource;
import ai.helper2.setup.usecase.GetResourceSetup;
import ai.helper2.shared.UtcTimes;
import org.jspecify.annotations.Nullable;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
class SetupController implements SetupApi {

    /** The registry asks aggregators to read it infrequently; an hour is how often the Scout reads it too. */
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofHours(1)).cachePublic();

    private final GetResourceSetup setup;
    private final AskAboutResource ask;

    SetupController(GetResourceSetup setup, AskAboutResource ask) {
        this.setup = setup;
        this.ask = ask;
    }

    @Override
    public ResponseEntity<SetupGuideDto> getResourceSetup(UUID id) {
        SetupGuide guide;
        try {
            guide = setup.setup(id).orElseThrow(() -> new NotFoundException("Resource not found."));
        } catch (SetupUnavailable e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The setup data could not be read now. Try again later.");
        }
        return ResponseEntity.ok().cacheControl(CACHE).body(dto(id, guide));
    }

    @Override
    public ResponseEntity<ToolGuideDto> askResourceGuide(UUID id, @Nullable ToolGuideRequestDto body) {
        AskAboutResource.Answered a = ask.ask(id, body == null ? null : body.getQuestion())
            .orElseThrow(() -> new NotFoundException("Resource not found."));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(dto(id, a.title(), a.answer()));
    }

    static ToolGuideDto dto(UUID id, String title, ToolGuideAnswer a) {
        return new ToolGuideDto(id, title, ToolGuideDto.StatusEnum.fromValue(a.getStatus().name()),
            a.getSections().stream().map(s -> new ToolGuideSectionsInnerDto(ToolGuideSectionsInnerDto.KindEnum.fromValue(s.getKind().name()),
                s.getSteps().stream().map(SetupController::step).toList())).toList(),
            a.getMaterials().stream().map(m -> new ToolGuideDocumentsInnerDto(m.getKey(),
                ToolGuideDocumentsInnerDto.KindEnum.fromValue(m.getKind().name()), m.getUrl(), UtcTimes.odt(m.getRetrievedAt()))).toList(),
            a.getRejected(), UtcTimes.odt(a.getGeneratedAt()))
            .question(a.getQuestion()).model(a.getModel()).unavailable(a.getUnavailable());
    }

    private static ToolGuideSectionsInnerStepsInnerDto step(GuideStep s) {
        return new ToolGuideSectionsInnerStepsInnerDto(s.getText(), s.getQuote(), s.getMaterialKey(), s.getCaution())
            .code(s.getCode() == null ? null : new ToolGuideSectionsInnerStepsInnerCodeDto(s.getCode().getText()).language(s.getCode().getLanguage()));
    }

    static SetupGuideDto dto(UUID id, SetupGuide g) {
        return new SetupGuideDto(id, g.getTitle(),
            g.getOptions().stream().map(SetupController::option).toList(),
            g.getClients().stream().map(SetupController::client).toList(),
            g.getSources().stream().map(s -> new SetupGuideSourcesInnerDto(s.getSourceId(), s.getUrl(), UtcTimes.odt(s.getRetrievedAt()))).toList())
            .version(g.getVersion());
    }

    private static SetupOptionDto option(ShownOption o) {
        RunOption r = o.getOption();
        SetupOptionDto dto = new SetupOptionDto(o.getId(),
            r instanceof RunOption.Remote ? SetupOptionDto.KindEnum.REMOTE
                : r instanceof RunOption.Command ? SetupOptionDto.KindEnum.COMMAND : SetupOptionDto.KindEnum.PACKAGE,
            SetupOptionDto.TransportEnum.fromValue(r.getTransport().name()), o.getLabel(),
            r.getSettings().stream().map(SetupController::setting).toList());
        if (r instanceof RunOption.Remote remote) {
            dto.url(remote.getUrl());
        } else if (r instanceof RunOption.Package p) {
            dto.registryType(p.getRegistryType()).identifier(p.getIdentifier());
        }
        if (o.getLaunch() != null) {
            dto.runtime(o.getLaunch().getCommand()).requires(o.getLaunch().getRequires());
        }
        return dto;
    }

    private static SetupSettingDto setting(Setting s) {
        return new SetupSettingDto(s.getName(), SetupSettingDto.KindEnum.fromValue(s.getKind().name()), s.getRequired(), s.getSecret())
            .description(s.getDescription())
            .value(s.getValue() == null ? null : SetupGuides.shownValue(s));
    }

    private static SetupClientDto client(ClientGuide c) {
        return new SetupClientDto(c.getClient().getId(), c.getClient().getName(), c.getClient().getDocsUrl(),
            c.getSnippets().stream().map(s -> new SetupClientSnippetsInnerDto(s.getOptionId(), s.getSupported())
                .where(s.getWhere()).language(s.getLanguage()).text(s.getText()).note(s.getNote())).toList());
    }
}
