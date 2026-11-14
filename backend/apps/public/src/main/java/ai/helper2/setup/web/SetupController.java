package ai.helper2.setup.web;

import ai.helper2.api.AiContentMarking;
import ai.helper2.api.PublicCache;
import ai.helper2.api.common.model.AiContentMarkerDto;
import ai.helper2.api.generated.SetupApi;
import ai.helper2.api.generated.model.SetupClientDto;
import ai.helper2.api.generated.model.SetupClientSnippetsInnerDto;
import ai.helper2.api.generated.model.SetupGuideDto;
import ai.helper2.api.generated.model.SetupGuideSourcesInnerDto;
import ai.helper2.api.generated.model.SetupOptionDto;
import ai.helper2.api.generated.model.SetupSettingDto;
import ai.helper2.api.generated.model.ToolGuideDocumentsInnerDto;
import ai.helper2.api.generated.model.ToolGuideDto;
import ai.helper2.api.generated.model.ToolGuideSectionsInnerDto;
import ai.helper2.api.generated.model.ToolGuideSectionsInnerStepsInnerCodeDto;
import ai.helper2.api.generated.model.ToolGuideSectionsInnerStepsInnerDto;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.ClientKey;
import ai.helper2.platform.NotFoundException;
import ai.helper2.platform.ProblemType;
import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.setup.ClientGuide;
import ai.helper2.setup.GuideSection;
import ai.helper2.setup.GuideStep;
import ai.helper2.setup.RunOption;
import ai.helper2.setup.Setting;
import ai.helper2.setup.SetupGuide;
import ai.helper2.setup.SetupGuides;
import ai.helper2.setup.SetupUnavailable;
import ai.helper2.setup.ShownOption;
import ai.helper2.setup.ToolGuideAnswer;
import ai.helper2.setup.ToolGuideStatus;
import ai.helper2.setup.usecase.GetResourceSetup;
import ai.helper2.setup.usecase.GetToolGuide;
import ai.helper2.shared.UtcTimes;
import jakarta.servlet.http.HttpServletRequest;
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
class SetupController implements SetupApi {
  private static final CacheControl CACHE = PublicCache.LONG;

  static final String STEP_TEXT = "sections[].steps[].text";

  static final String CODE_LANGUAGE = "sections[].steps[].code.language";

  static final List<String> AI_WRITTEN_FIELDS = List.of(STEP_TEXT, CODE_LANGUAGE);

  static final String AI_MARKING_POLICY = AiContentMarking.POLICY;

  private final GetResourceSetup setup;
  private final GetToolGuide toolGuide;
  private final HttpServletRequest http;
  private final String provider;

  SetupController(
      GetResourceSetup setup, GetToolGuide toolGuide, LlmSettings llm, HttpServletRequest http) {
    this.setup = setup;
    this.toolGuide = toolGuide;
    this.provider = llm.provider();
    this.http = http;
  }

  @Override
  public ResponseEntity<SetupGuideDto> getResourceSetup(UUID id) {
    SetupGuide guide;
    try {
      guide = setup.setup(id).orElseThrow(() -> new NotFoundException("Resource not found."));
    } catch (SetupUnavailable e) {
      throw new ApiProblem(
          ProblemType.UNAVAILABLE, "The setup data could not be read now. Try again later.");
    }
    return ResponseEntity.ok().cacheControl(CACHE).body(dto(id, guide));
  }

  @Override
  public ResponseEntity<ToolGuideDto> askResourceGuide(UUID id) {
    GetToolGuide.Guided a =
        toolGuide
            .guide(id, ClientKey.of(http.getRemoteAddr()))
            .orElseThrow(() -> new NotFoundException("Resource not found."));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(dto(id, a.title(), a.answer(), provider));
  }

  static ToolGuideDto dto(UUID id, String title, ToolGuideAnswer a, String provider) {
    return new ToolGuideDto(
            id,
            title,
            ToolGuideDto.StatusEnum.fromValue(a.getStatus().name()),
            a.getSections().stream()
                .map(s ->
                    new ToolGuideSectionsInnerDto(
                        ToolGuideSectionsInnerDto.KindEnum.fromValue(s.getKind().name()),
                        s.getSteps().stream().map(SetupController::step).toList()))
                .toList(),
            a.getMaterials().stream()
                .map(m ->
                    new ToolGuideDocumentsInnerDto(
                        m.getKey(),
                        ToolGuideDocumentsInnerDto.KindEnum.fromValue(m.getKind().name()),
                        m.getUrl(),
                        UtcTimes.odt(m.getRetrievedAt())))
                .toList(),
            a.getRejected(),
            UtcTimes.odt(a.getGeneratedAt()))
        .model(a.getModel())
        .aiContent(marker(a, provider))
        .unavailable(a.getUnavailable())
        .retryAt(UtcTimes.odt(a.getRetryAt()));
  }

  static @Nullable AiContentMarkerDto marker(ToolGuideAnswer a, String provider) {
    if (a.getStatus() != ToolGuideStatus.OK || a.getModel() == null) {
      return null;
    }
    List<String> fields = aiWrittenFields(a.getSections());
    if (fields.isEmpty()) {
      return null;
    }
    return new AiContentMarkerDto(true, provider, List.of(a.getModel()), fields, AI_MARKING_POLICY);
  }

  static List<String> aiWrittenFields(List<GuideSection> sections) {
    List<String> fields = new ArrayList<>(shownStepTexts(sections));
    boolean namedLanguage =
        steps(sections).anyMatch(s -> s.getCode() != null && s.getCode().getLanguage() != null);
    if (namedLanguage) {
      fields.add(CODE_LANGUAGE);
    }
    return fields;
  }

  private static List<String> shownStepTexts(List<GuideSection> sections) {
    if (steps(sections).allMatch(s -> s.getText() != null)) {
      return steps(sections).findAny().isPresent() ? List.of(STEP_TEXT) : List.of();
    }
    List<String> paths = new ArrayList<>();
    for (int i = 0; i < sections.size(); i++) {
      List<GuideStep> steps = sections.get(i).getSteps();
      for (int j = 0; j < steps.size(); j++) {
        if (steps.get(j).getText() != null) {
          paths.add("sections[" + i + "].steps[" + j + "].text");
        }
      }
    }
    return paths;
  }

  private static Stream<GuideStep> steps(List<GuideSection> sections) {
    return sections.stream().flatMap(s -> s.getSteps().stream());
  }

  private static ToolGuideSectionsInnerStepsInnerDto step(GuideStep s) {
    return new ToolGuideSectionsInnerStepsInnerDto(
            s.getText(), s.getQuote(), s.getMaterialKey(), s.getCaution())
        .code(
            s.getCode() == null
                ? null
                : new ToolGuideSectionsInnerStepsInnerCodeDto(s.getCode().getText())
                    .language(s.getCode().getLanguage()));
  }

  static SetupGuideDto dto(UUID id, SetupGuide g) {
    return new SetupGuideDto(
            id,
            g.getTitle(),
            g.getOptions().stream().map(SetupController::option).toList(),
            g.getClients().stream().map(SetupController::client).toList(),
            g.getSources().stream()
                .map(s ->
                    new SetupGuideSourcesInnerDto(
                        s.getSourceId(), s.getUrl(), UtcTimes.odt(s.getRetrievedAt())))
                .toList())
        .version(g.getVersion());
  }

  private static SetupOptionDto option(ShownOption o) {
    RunOption r = o.getOption();
    SetupOptionDto dto =
        new SetupOptionDto(
            o.getId(),
            r instanceof RunOption.Remote
                ? SetupOptionDto.KindEnum.REMOTE
                : r instanceof RunOption.Command
                    ? SetupOptionDto.KindEnum.COMMAND
                    : SetupOptionDto.KindEnum.PACKAGE,
            SetupOptionDto.TransportEnum.fromValue(r.getTransport().name()),
            o.getLabel(),
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
    return new SetupSettingDto(
            s.getName(),
            SetupSettingDto.KindEnum.fromValue(s.getKind().name()),
            s.getRequired(),
            s.getSecret())
        .description(s.getDescription())
        .value(s.getValue() == null ? null : SetupGuides.shownValue(s));
  }

  private static SetupClientDto client(ClientGuide c) {
    return new SetupClientDto(
        c.getClient().getId(),
        c.getClient().getName(),
        c.getClient().getDocsUrl(),
        c.getSnippets().stream()
            .map(s ->
                new SetupClientSnippetsInnerDto(s.getOptionId(), s.getSupported())
                    .where(s.getWhere())
                    .language(s.getLanguage())
                    .text(s.getText())
                    .note(s.getNote()))
            .toList());
  }
}
