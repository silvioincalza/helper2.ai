package ai.helper2.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.json.JsonMapper;

class ProblemsTest {

  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void aProblemCarriesItsTypeUriTitleStatusAndCode() {
    ProblemDetail problem = Problems.of(ProblemType.VERSION_CONFLICT, "Someone changed it first.");

    assertThat(problem.getType()).hasToString("https://helper2.ai/problems/version-conflict");
    assertThat(problem.getTitle()).isEqualTo("Changed by someone else");
    assertThat(problem.getStatus()).isEqualTo(409);
    assertThat(problem.getDetail()).isEqualTo("Someone changed it first.");
    assertThat(problem.getProperties()).containsEntry("code", "version-conflict");
  }

  @Test
  void theJsonWrittenBeforeSpringMvcIsTheSameProblem() {
    @SuppressWarnings("unchecked")
    Map<String, Object> written =
        json.readValue(Problems.json(ProblemType.RATE_LIMITED, "Try again in \"5\" s."), Map.class);

    assertThat(written)
        .containsEntry("type", "https://helper2.ai/problems/rate-limited")
        .containsEntry("title", "Too many requests")
        .containsEntry("status", 429)
        .containsEntry("detail", "Try again in \"5\" s.")
        .containsEntry("code", "rate-limited");
    @SuppressWarnings("unchecked")
    Map<String, Object> plain =
        json.readValue(Problems.json(ProblemType.NOT_FOUND, null), Map.class);
    assertThat(plain).doesNotContainKey("detail");
  }

  @Test
  void everyCodeIsUniqueAndUrlSafe() {
    assertThat(Arrays.stream(ProblemType.values()).map(ProblemType::code))
        .doesNotHaveDuplicates()
        .allMatch(code -> code.matches("[a-z]+(-[a-z]+)*"));
  }

  @Test
  void aStatusTheFrameworkAnswersOnItsOwnHasAType() {
    assertThat(ProblemType.forStatus(405)).isEqualTo(ProblemType.METHOD_NOT_ALLOWED);
    assertThat(ProblemType.forStatus(502)).isEqualTo(ProblemType.INTERNAL);
    assertThat(ProblemType.forStatus(418)).isEqualTo(ProblemType.INVALID_REQUEST);
  }
}
