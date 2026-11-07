package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.catalog.ReviewEvidenceFixture;
import ai.helper2.catalog.internal.ResourceCards;
import ai.helper2.catalog.internal.ResourceStore;
import ai.helper2.catalog.internal.ResourceStore.DataOrigin;
import ai.helper2.search.ResultMerger;
import ai.helper2.search.ServedFrom;
import ai.helper2.search.SourceContext;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.Pricing;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;
import org.springframework.test.web.servlet.ResultActions;

/**
 * PUT /admin/resources/{id}/fields/{field} and POST …/drop (ADR 0066): an admin's value wins over
 * the AI and over a later record; dropping removes only the AI's values.
 */
class CuratedFieldApiIT extends ApiTestBase {

  @Autowired JdbcClient jdbc;
  @Autowired ResourceStore store;
  @Autowired ResourceCards cards;

  ReviewEvidenceFixture fixture;
  final String adminId = "fields-" + UUID.randomUUID();
  final OAuth2LoginRequestPostProcessor admin =
      oauth2Login()
          .attributes(a -> a.put("sub", adminId))
          .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

  @BeforeEach
  void insert() {
    fixture = new ReviewEvidenceFixture(jdbc).insert();
  }

  @AfterEach
  void clean() {
    fixture.delete();
  }

  ResultActions set(UUID resource, String field, String value) throws Exception {
    return mvc.perform(
        put("/api/v1/admin/resources/" + resource + "/fields/" + field)
            .with(admin)
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"value\":\"" + value + "\"}"));
  }

  String column(String sql) {
    return jdbc.sql("SELECT CAST(" + sql + " AS text) FROM resource WHERE id = :id")
        .param("id", fixture.resource)
        .query(String.class)
        .single();
  }

  List<String> assertions(String field) {
    return jdbc.sql(
            """
            SELECT origin || ' ' || verification || ' ' || value FROM field_assertion
            WHERE resource_id = :id AND field = :f ORDER BY origin
            """)
        .param("id", fixture.resource)
        .param("f", field)
        .query(String.class)
        .list();
  }

  List<String> audit() {
    return jdbc.sql(
            "SELECT action || ' ' || outcome FROM admin_audit WHERE admin_id = :a ORDER BY at, id")
        .param("a", adminId)
        .query(String.class)
        .list();
  }

  /** A later record of the resource that states another price and type. */
  void indexAnotherRecord() {
    String url = fixture.url;
    SourceHit hit =
        new SourceHit(
            ReviewEvidenceFixture.SOURCE,
            "later-" + UUID.randomUUID(),
            URI.create(url),
            CanonicalUrl.of(url),
            null,
            "Acme Tool",
            "An AI tool",
            ai.helper2.shared.ValueOrigin.SOURCE_PROVIDED,
            ResourceType.PRODUCT,
            ai.helper2.shared.Audience.BUILD_WITH_AI,
            null,
            Set.of(),
            Set.of(),
            null,
            Pricing.FREE,
            Set.of(),
            null,
            ai.helper2.shared.DateFact.unknown(),
            ai.helper2.shared.DateFact.unknown(),
            List.of(),
            Instant.now(),
            0);
    SourceDescriptor d =
        new SourceDescriptor(
            ReviewEvidenceFixture.SOURCE,
            "test",
            "test",
            true,
            SourceMode.INDEXED,
            URI.create("https://helper2.ai/sources"),
            null,
            null,
            EnumSet.allOf(ResourceType.class),
            Duration.ofSeconds(1),
            Duration.ZERO);
    store.upsert(
        new ResultMerger()
            .merge(
                List.of(hit),
                Map.of(ReviewEvidenceFixture.SOURCE, new SourceContext(d, ServedFrom.LIVE))),
        DataOrigin.INDEXED,
        Set.of(ReviewEvidenceFixture.SOURCE));
  }

  @Test
  void anAdminsValueWinsOverTheAiAndOverALaterRecord() throws Exception {
    set(fixture.resource, "pricing", "PAID").andExpect(status().isNoContent());
    set(fixture.resource, "type", "MODEL").andExpect(status().isNoContent());

    assertThat(column("pricing")).isEqualTo("PAID");
    assertThat(column("curated_fields")).isEqualTo("{pricing,type}");
    assertThat(assertions("pricing"))
        .containsExactly("AI_EXTRACTED REJECTED FREE", "CURATED VERIFIED PAID");

    indexAnotherRecord();

    assertThat(column("pricing")).isEqualTo("PAID");
    assertThat(column("type")).isEqualTo("MODEL");
    mvc.perform(get("/api/v1/resources/" + fixture.resource))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.card.pricing").value("PAID"))
        .andExpect(jsonPath("$.card.type").value("MODEL"));
    assertThat(cards.cardsOf(List.of(fixture.resource)))
        .singleElement()
        .satisfies(card -> {
          assertThat(card.getPricing()).isEqualTo(Pricing.PAID);
          assertThat(card.getType()).isEqualTo(ResourceType.MODEL);
        });
    assertThat(audit()).containsExactly("SET_FIELD SET pricing", "SET_FIELD SET type");
  }

  @Test
  void droppingRemovesOnlyTheAisValues() throws Exception {
    mvc.perform(
            post("/api/v1/admin/resources/" + fixture.resource + "/fields/license/drop")
                .with(admin)
                .with(csrf()))
        .andExpect(status().isNoContent());

    assertThat(assertions("license")).containsExactly("AI_EXTRACTED REJECTED MIT");
    assertThat(audit()).containsExactly("DROP_FIELD DROPPED license 1");
  }

  @Test
  void aFieldOrValueTheResourceCannotHaveIsRefused() throws Exception {
    set(fixture.resource, "useCase", "coding").andExpect(status().isBadRequest());
    set(fixture.resource, "pricing", "CHEAP").andExpect(status().isBadRequest());
    set(fixture.resource, "type", "OTHER").andExpect(status().isBadRequest());
    set(fixture.resource, "license", "not a licence").andExpect(status().isBadRequest());
    set(UUID.randomUUID(), "pricing", "PAID").andExpect(status().isNotFound());
    set(fixture.resource, "nonsense", "x").andExpect(status().isBadRequest());

    assertThat(column("curated_fields")).isEqualTo("{}");
    assertThat(audit()).isEmpty();
  }

  @Test
  void onlyAdminsSetAFieldAndOnlyWithTheCsrfToken() throws Exception {
    String path = "/api/v1/admin/resources/" + fixture.resource + "/fields/pricing";
    String body = "{\"value\":\"PAID\"}";
    mvc.perform(put(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            put(path)
                .with(oauth2Login())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(put(path).with(admin).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isForbidden());
  }
}
