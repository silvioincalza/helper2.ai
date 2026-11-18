package ai.helper2.api;

import ai.helper2.search.SharedApiTestBase;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {
      "helper2.guide.fairness.budget-pace-slots=1",
      "helper2.pages.template=classpath:/pages/index.html",
    })
abstract class ApiTestBase extends SharedApiTestBase {}
