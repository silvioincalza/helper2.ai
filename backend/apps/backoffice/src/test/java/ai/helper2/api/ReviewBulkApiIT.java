package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.catalog.ReviewEvidenceFixture;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

class ReviewBulkApiIT extends ApiTestBase {

  static final RequestPostProcessor ADMIN =
      oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  static final String PATH = "/api/v1/admin/review-items/resolutions";

  @Autowired JdbcClient jdbc;

  ReviewEvidenceFixture fixture;

  @BeforeEach
  void oneRunWithItsItems() {
    fixture = new ReviewEvidenceFixture(jdbc).insert();
  }

  @AfterEach
  void clean() {
    fixture.delete();
  }

  private static MockHttpServletRequestBuilder bulk(String status, List<UUID> ids) {
    String list = ids.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
    return post(PATH)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"status\":\"" + status + "\",\"ids\":[" + list + "]}");
  }

  private String itemStatus(UUID item) {
    return jdbc.sql("SELECT status FROM review_item WHERE id = :id")
        .param("id", item)
        .query(String.class)
        .single();
  }

  private List<String> audit(UUID item) {
    return jdbc.sql("SELECT outcome FROM admin_audit WHERE target_id = :id ORDER BY at")
        .param("id", item.toString())
        .query(String.class)
        .list();
  }

  @Test
  void onlyAdminsWithTheCsrfTokenDecide() throws Exception {
    List<UUID> one = List.of(fixture.reportItem);
    mvc.perform(bulk("REJECTED", one).with(csrf().asHeader())).andExpect(status().isUnauthorized());
    mvc.perform(bulk("REJECTED", one).with(oauth2Login()).with(csrf().asHeader()))
        .andExpect(status().isForbidden());
    mvc.perform(bulk("REJECTED", one).with(ADMIN)).andExpect(status().isForbidden());
    assertThat(itemStatus(fixture.reportItem)).isEqualTo("OPEN");
  }

  @Test
  void noItemsOrMoreThanOneHundredIsABadRequest() throws Exception {
    mvc.perform(bulk("REJECTED", List.of()).with(ADMIN).with(csrf().asHeader()))
        .andExpect(status().isBadRequest());
    List<UUID> many = IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID()).toList();
    mvc.perform(bulk("REJECTED", many).with(ADMIN).with(csrf().asHeader()))
        .andExpect(status().isBadRequest());
    mvc.perform(bulk("OPEN", List.of(fixture.reportItem)).with(ADMIN).with(csrf().asHeader()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aRepeatedIdIsABadRequestAndDecidesNothing() throws Exception {
    List<UUID> twice = List.of(fixture.reportItem, fixture.reportItem);

    mvc.perform(bulk("REJECTED", twice).with(ADMIN).with(csrf().asHeader()))
        .andExpect(status().isBadRequest());

    assertThat(itemStatus(fixture.reportItem)).isEqualTo("OPEN");
    assertThat(audit(fixture.reportItem)).isEmpty();
  }

  @Test
  void bulkAcceptDecidesOnlyTheSafeItemsAndRecordsEachWithTheBatch() throws Exception {
    UUID safe = fixture.enrichmentItemConflictingOn("platforms");
    UUID licence = fixture.enrichmentItemConflictingOn("license");
    UUID unknown = UUID.randomUUID();
    List<UUID> ids =
        List.of(
            safe,
            fixture.validationItem,
            licence,
            fixture.relationshipItem,
            fixture.reportItem,
            unknown);

    String body =
        mvc.perform(bulk("ACCEPTED", ids).with(ADMIN).with(csrf().asHeader()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    ContractValidator.assertMatches("ReviewBulkResult", body);
    assertThat(JsonPath.<List<String>>read(body, "$.items[*].id"))
        .containsExactlyElementsOf(ids.stream().map(UUID::toString).toList());
    assertThat(JsonPath.<List<String>>read(body, "$.items[*].outcome"))
        .containsExactly(
            "RESOLVED", "NOT_ALLOWED", "NOT_ALLOWED", "NOT_ALLOWED", "NOT_ALLOWED", "NOT_FOUND");
    assertThat(itemStatus(safe)).isEqualTo("ACCEPTED");
    assertThat(itemStatus(fixture.validationItem)).isEqualTo("OPEN");
    assertThat(itemStatus(licence)).isEqualTo("OPEN");
    assertThat(itemStatus(fixture.relationshipItem)).isEqualTo("OPEN");
    assertThat(itemStatus(fixture.reportItem)).isEqualTo("OPEN");
    String batch = JsonPath.read(body, "$.batch");
    assertThat(audit(safe)).containsExactly("ACCEPTED bulk:" + batch);
    assertThat(audit(fixture.validationItem)).containsExactly("NOT_ALLOWED bulk:" + batch);
    assertThat(audit(unknown)).containsExactly("NOT_FOUND bulk:" + batch);
  }

  @Test
  void bulkRejectDecidesEveryKindOnceAndAnItemDecidedBeforeIsNotOpen() throws Exception {
    List<UUID> ids = List.of(fixture.validationItem, fixture.relationshipItem, fixture.reportItem);

    String first =
        mvc.perform(bulk("REJECTED", ids).with(ADMIN).with(csrf().asHeader()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String again =
        mvc.perform(
                bulk("REJECTED", List.of(fixture.reportItem)).with(ADMIN).with(csrf().asHeader()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(JsonPath.<List<String>>read(first, "$.items[*].outcome")).containsOnly("RESOLVED");
    assertThat(ids).allSatisfy(id -> assertThat(itemStatus(id)).isEqualTo("REJECTED"));
    assertThat(
            jdbc.sql(
                    "SELECT r.verification FROM relationship r JOIN review_item i ON i.relationship_id = r.id WHERE i.id = :id")
                .param("id", fixture.relationshipItem)
                .query(String.class)
                .single())
        .isEqualTo("REJECTED");
    assertThat(JsonPath.<List<String>>read(again, "$.items[*].outcome"))
        .containsExactly("NOT_OPEN");
    assertThat(audit(fixture.reportItem)).hasSize(2).last().asString().startsWith("NOT_OPEN bulk:");
  }
}
