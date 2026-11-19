package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.catalog.CatalogWriter;
import ai.helper2.catalog.DataOrigin;
import ai.helper2.catalog.ResourceCards;
import ai.helper2.catalog.internal.ReviewEvidenceFixture;
import ai.helper2.catalog.usecase.CurateField;
import ai.helper2.platform.ProblemType;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class CuratedFieldApiIT extends ApiTestBase {

  @Autowired JdbcClient jdbc;
  @Autowired CatalogWriter writer;
  @Autowired ResourceCards cards;
  @Autowired CurateField curate;
  @Autowired PlatformTransactionManager transactions;

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
            .with(csrf().asHeader())
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

  List<String> changes() {
    return jdbc.sql(
            """
            SELECT outcome || ': ' || coalesce(before_value, '-') || ' -> ' || coalesce(after_value, '-')
              FROM admin_audit WHERE admin_id = :a ORDER BY at, id
            """)
        .param("a", adminId)
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
    writer.upsert(
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
    assertThat(detail(fixture.resource.toString()).card().getPricing()).isEqualTo(Pricing.PAID);
    assertThat(detail(fixture.resource.toString()).card().getType()).isEqualTo(ResourceType.MODEL);
    assertThat(cards.cardsOf(List.of(fixture.resource)))
        .singleElement()
        .satisfies(card -> {
          assertThat(card.getPricing()).isEqualTo(Pricing.PAID);
          assertThat(card.getType()).isEqualTo(ResourceType.MODEL);
        });
    assertThat(audit()).containsExactly("SET_FIELD SET pricing", "SET_FIELD SET type");
  }

  @Test
  void eachSetSaysTheValueBeforeAndAfterAndWhetherAPersonHadSetItBefore() throws Exception {
    String before = column("coalesce(pricing, '(none)')");
    set(fixture.resource, "pricing", "PAID").andExpect(status().isNoContent());
    set(fixture.resource, "pricing", "FREEMIUM").andExpect(status().isNoContent());
    set(fixture.resource, "language", "de").andExpect(status().isNoContent());

    assertThat(changes())
        .containsExactly(
            "SET pricing: " + before + " -> PAID",
            "SET pricing: PAID (curated) -> FREEMIUM",
            "SET language: en -> de");
  }

  @Test
  void anEditWaitsForAnotherOnTheSameResourceAndRecordsTheValueItOverwrote() throws Exception {
    TransactionTemplate tx = new TransactionTemplate(transactions);
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService first = Executors.newSingleThreadExecutor();
    Future<?> firstEdit =
        first.submit(() ->
            tx.executeWithoutResult(s -> {
              curate.set(fixture.resource, "pricing", "PAID", adminId);
              held.countDown();
              awaitQuietly(release);
            }));
    assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
    Thread second = new Thread(() -> curate.set(fixture.resource, "pricing", "FREEMIUM", adminId));
    second.start();
    second.join(500);

    assertThat(second.isAlive()).isTrue();

    release.countDown();
    firstEdit.get(10, TimeUnit.SECONDS);
    second.join(10_000);
    first.shutdown();
    assertThat(changes()).contains("SET pricing: PAID (curated) -> FREEMIUM");
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void droppingRemovesOnlyTheAisValues() throws Exception {
    mvc.perform(
            post("/api/v1/admin/resources/" + fixture.resource + "/fields/license/drop")
                .with(admin)
                .with(csrf().asHeader()))
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

  ResultActions drop(UUID resource, String field) throws Exception {
    return mvc.perform(
        post("/api/v1/admin/resources/" + resource + "/fields/" + field + "/drop")
            .with(admin)
            .with(csrf().asHeader()));
  }

  @Test
  void aFieldThatCannotBeSetIsRefusedWithItsName() throws Exception {
    set(fixture.resource, "useCase", "coding")
        .andExpect(problem(ProblemType.INVALID_REQUEST, "This field cannot be set: useCase."));

    assertNothingChanged();
  }

  @Test
  void aValueTheFieldCannotTakeIsRefusedWithTheFieldsName() throws Exception {
    set(fixture.resource, "pricing", "CHEAP")
        .andExpect(problem(ProblemType.INVALID_REQUEST, "This value is not allowed for pricing."));
    set(fixture.resource, "language", "xx")
        .andExpect(problem(ProblemType.INVALID_REQUEST, "This value is not allowed for language."));

    assertNothingChanged();
  }

  @Test
  void anEmptyDescriptionIsRefusedWithTheLengthItMustHave() throws Exception {
    set(fixture.resource, "description", " ")
        .andExpect(
            problem(ProblemType.INVALID_REQUEST, "The description must be 1 to 4000 characters."));

    assertNothingChanged();
  }

  @Test
  void aPublishedDateThatIsNoDateIsRefusedWithTheFormatItMustHave() throws Exception {
    set(fixture.resource, "publishedAt", "2 March")
        .andExpect(problem(ProblemType.INVALID_REQUEST, "The published date must be YYYY-MM-DD."));

    assertNothingChanged();
  }

  @Test
  void aPublishedDateInTheFutureIsRefusedAsSuch() throws Exception {
    set(fixture.resource, "publishedAt", "2999-01-01")
        .andExpect(problem(ProblemType.INVALID_REQUEST, "The published date is in the future."));

    assertNothingChanged();
  }

  @Test
  void aDropOfAFieldTheAiDoesNotWriteIsRefusedWithItsName() throws Exception {
    drop(fixture.resource, "description")
        .andExpect(
            problem(ProblemType.INVALID_REQUEST, "The AI writes no such field: description."));

    assertNothingChanged();
  }

  @Test
  void aSetOrDropOnAResourceThatIsGoneIsNotFound() throws Exception {
    String missing = "Resource not found.";
    UUID unknown = UUID.randomUUID();

    set(unknown, "pricing", "PAID").andExpect(problem(ProblemType.NOT_FOUND, missing));
    set(unknown, "description", "Writes notes.").andExpect(problem(ProblemType.NOT_FOUND, missing));
    set(unknown, "publishedAt", "2025-03-02").andExpect(problem(ProblemType.NOT_FOUND, missing));
    drop(unknown, "license").andExpect(problem(ProblemType.NOT_FOUND, missing));

    assertNothingChanged();
  }

  private void assertNothingChanged() {
    assertThat(column("curated_fields")).isEqualTo("{}");
    assertThat(audit()).isEmpty();
  }

  @Test
  void aDescriptionLanguageAndPublishedDateAreSetAsTheAdminsOwn() throws Exception {
    set(fixture.resource, "description", "Writes meeting notes for small teams.")
        .andExpect(status().isNoContent());
    set(fixture.resource, "language", "de").andExpect(status().isNoContent());
    set(fixture.resource, "publishedAt", "2025-03-02").andExpect(status().isNoContent());

    assertThat(column("description")).isEqualTo("Writes meeting notes for small teams.");
    assertThat(column("description_origin")).isEqualTo("CURATED");
    assertThat(column("language")).isEqualTo("de");
    assertThat(column("to_char(published_at AT TIME ZONE 'UTC', 'YYYY-MM-DD')"))
        .isEqualTo("2025-03-02");
    assertThat(column("published_precision")).isEqualTo("DAY");
    assertThat(column("array_to_string(curated_fields, ',')").split(","))
        .containsExactlyInAnyOrder("description", "language", "publishedAt");
    assertThat(audit())
        .containsExactly(
            "SET_FIELD SET description CURATED",
            "SET_FIELD SET language",
            "SET_FIELD SET publishedAt");
  }

  @Test
  void aBadDescriptionLanguageOrDateIsRefusedAndTheyCannotBeDropped() throws Exception {
    set(fixture.resource, "language", "xx").andExpect(status().isBadRequest());
    set(fixture.resource, "publishedAt", "2 March").andExpect(status().isBadRequest());
    set(fixture.resource, "publishedAt", "2999-01-01").andExpect(status().isBadRequest());
    set(fixture.resource, "description", " ").andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/admin/resources/" + fixture.resource + "/fields/description/drop")
                .with(admin)
                .with(csrf().asHeader()))
        .andExpect(status().isBadRequest());

    assertThat(column("curated_fields")).isEqualTo("{}");
    assertThat(audit()).isEmpty();
  }

  @Test
  void onlyAdminsSetAFieldAndOnlyWithTheCsrfToken() throws Exception {
    String path = "/api/v1/admin/resources/" + fixture.resource + "/fields/pricing";
    String body = "{\"value\":\"PAID\"}";
    mvc.perform(
            put(path).with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            put(path)
                .with(oauth2Login())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(put(path).with(admin).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isForbidden());
  }
}
