package ai.searchavo.api;

import ai.searchavo.search.SharedApiTestBase;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {
      "searchavo.guide.fairness.budget-pace-slots=1",
      "searchavo.pages.template=classpath:/pages/index.html",
    })
abstract class ApiTestBase extends SharedApiTestBase {}
