package ai.helper2.api;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.catalog.ResourceDetail;
import ai.helper2.catalog.usecase.GetResourceDetail;
import ai.helper2.platform.ProblemType;
import ai.helper2.search.SharedApiTestBase;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.ResultMatcher;

@Import({SameOriginRequests.class, SessionsComeFromASignIn.class})
abstract class ApiTestBase extends SharedApiTestBase {

  @Autowired GetResourceDetail resourceDetail;

  ResourceDetail detail(String id) {
    return resourceDetail.find(UUID.fromString(id)).orElseThrow();
  }

  static ResultMatcher problem(ProblemType type, String detail) {
    List<ResultMatcher> parts =
        List.of(
            status().is(type.status().value()),
            jsonPath("$.type").value(type.uri().toString()),
            jsonPath("$.code").value(type.code()),
            jsonPath("$.title").value(type.title()),
            jsonPath("$.detail").value(detail));
    return result -> {
      for (ResultMatcher part : parts) {
        part.match(result);
      }
    };
  }
}
