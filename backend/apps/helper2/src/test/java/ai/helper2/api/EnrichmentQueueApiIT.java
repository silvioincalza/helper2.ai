package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** GET /admin/enrichment-queue: ADMIN only, the contract's shape, paged by cursor to the end. */
class EnrichmentQueueApiIT extends ApiTestBase {

  static final RequestPostProcessor ADMIN =
      oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

  @Test
  void onlyAdminsSeeTheQueue() throws Exception {
    mvc.perform(get("/api/v1/admin/enrichment-queue")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/admin/enrichment-queue").with(oauth2Login()))
        .andExpect(status().isForbidden());
  }

  @Test
  void theNextCursorWalksTheWholeQueueOnce() throws Exception {
    List<String> seen = new ArrayList<>();
    String after = null;
    for (int page = 0; page < 1_000; page++) {
      var request = get("/api/v1/admin/enrichment-queue").param("size", "2").with(ADMIN);
      if (after != null) {
        request = request.param("after", after);
      }
      String body =
          mvc.perform(request)
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      ContractValidator.assertMatches("EnrichmentQueuePage", body);
      seen.addAll(JsonPath.read(body, "$.items[*].resourceId"));
      after = JsonPath.read(body, "$.next");
      if (after == null) {
        break;
      }
    }
    assertThat(after).as("the last page has no next").isNull();
    assertThat(seen).doesNotHaveDuplicates();
  }

  @Test
  void aForeignCursorOrSizeIsABadRequest() throws Exception {
    mvc.perform(get("/api/v1/admin/enrichment-queue").param("after", "nope").with(ADMIN))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/admin/enrichment-queue").param("size", "0").with(ADMIN))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/admin/enrichment-queue").param("size", "101").with(ADMIN))
        .andExpect(status().isBadRequest());
  }
}
