package ai.helper2.examples.web;

import ai.helper2.api.AiContentMarking;
import ai.helper2.api.PublicCache;
import ai.helper2.api.common.model.AiContentMarkerDto;
import ai.helper2.api.common.model.AudiencePathDto;
import ai.helper2.api.generated.ExamplesApi;
import ai.helper2.api.generated.model.HomeExampleDto;
import ai.helper2.api.generated.model.HomeExamplePoolDto;
import ai.helper2.examples.ExampleOrigin;
import ai.helper2.examples.HomeExample;
import ai.helper2.examples.usecase.ListHomeExamples;
import ai.helper2.platform.llm.LlmSettings;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class HomeExamplesController implements ExamplesApi {
  static final String AI_MARKING_POLICY = AiContentMarking.POLICY;

  private static final CacheControl CACHE = PublicCache.SHORT;

  private final ListHomeExamples list;
  private final LlmSettings llm;

  HomeExamplesController(ListHomeExamples list, LlmSettings llm) {
    this.list = list;
    this.llm = llm;
  }

  @Override
  public ResponseEntity<HomeExamplePoolDto> listHomeExamples(String lang) {
    List<HomeExample> served = list.served(lang);
    return ResponseEntity.ok()
        .cacheControl(CACHE)
        .body(
            new HomeExamplePoolDto(served.stream().map(HomeExamplesController::dto).toList())
                .ai(marker(served)));
  }

  private static HomeExampleDto dto(HomeExample e) {
    return new HomeExampleDto(
        e.getId(),
        e.getLabel(),
        e.getQuery(),
        AudiencePathDto.fromValue(e.getPath().name()),
        drafted(e));
  }

  private @Nullable AiContentMarkerDto marker(List<HomeExample> served) {
    List<String> models =
        served.stream()
            .filter(HomeExamplesController::drafted)
            .map(HomeExample::getModel)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    if (models.isEmpty()) {
      return null;
    }
    return new AiContentMarkerDto(
        true,
        llm.provider(),
        models,
        List.of("examples[].label", "examples[].query"),
        AI_MARKING_POLICY);
  }

  private static boolean drafted(HomeExample e) {
    return e.getOrigin() == ExampleOrigin.AI_DRAFTED;
  }
}
