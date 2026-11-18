package ai.helper2.api;

import ai.helper2.catalog.ResourceDetail;
import ai.helper2.catalog.usecase.GetResourceDetail;
import ai.helper2.search.SharedApiTestBase;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import({SameOriginRequests.class, SessionsComeFromASignIn.class})
abstract class ApiTestBase extends SharedApiTestBase {

  @Autowired GetResourceDetail resourceDetail;

  ResourceDetail detail(String id) {
    return resourceDetail.find(UUID.fromString(id)).orElseThrow();
  }
}
