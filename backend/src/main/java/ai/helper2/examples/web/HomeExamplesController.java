package ai.helper2.examples.web;

import ai.helper2.api.generated.ExamplesApi;
import ai.helper2.api.generated.model.AudiencePathDto;
import ai.helper2.api.generated.model.HomeExampleDto;
import ai.helper2.api.generated.model.HomeExamplePoolDto;
import ai.helper2.examples.HomeExample;
import ai.helper2.examples.usecase.ListHomeExamples;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The pool the home page picks its examples from: the same answer for everyone, cached. */
@RestController
@RequestMapping("/api/v1")
class HomeExamplesController implements ExamplesApi {

  private static final CacheControl CACHE =
      CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

  private final ListHomeExamples list;

  HomeExamplesController(ListHomeExamples list) {
    this.list = list;
  }

  @Override
  public ResponseEntity<HomeExamplePoolDto> listHomeExamples(String lang) {
    return ResponseEntity.ok()
        .cacheControl(CACHE)
        .body(
            new HomeExamplePoolDto(
                list.served(lang).stream().map(HomeExamplesController::dto).toList()));
  }

  private static HomeExampleDto dto(HomeExample e) {
    return new HomeExampleDto(
        e.getId(), e.getLabel(), e.getQuery(), AudiencePathDto.fromValue(e.getPath().name()));
  }
}
