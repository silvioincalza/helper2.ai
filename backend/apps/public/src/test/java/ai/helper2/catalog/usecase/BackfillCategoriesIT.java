package ai.helper2.catalog.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.internal.ResourceStore;
import ai.helper2.shared.CategoryCatalog;
import ai.helper2.testing.AsBackOffice;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The category backfill (ADR 0053) against real PostgreSQL. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AsBackOffice
class BackfillCategoriesIT {

  @Autowired BackfillCategories backfill;

  @Autowired ResourceStore store;

  @Autowired CategoryCatalog catalog;

  @Autowired JdbcClient jdbc;

  private UUID seedWithoutCategory(String title) {
    UUID id = UUID.randomUUID();
    String url = "https://backfill.example/" + id;
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
            VALUES (:id, :url, :url, :title, 'SOURCE_PROVIDED', 'PRODUCT', 'USE_AI', 'INDEXED')
            """)
        .param("id", id)
        .param("url", url)
        .param("title", title)
        .update();
    return id;
  }

  private int categoryLabels(UUID id) {
    return jdbc.sql(
            "SELECT count(*) FROM resource_label WHERE resource_id = :id AND kind = 'CATEGORY'")
        .param("id", id)
        .query(Integer.class)
        .single();
  }

  @Test
  void itAssignsEveryResourceOnceThenOnlyWhenTheWordsChange() {
    jdbc.sql("DELETE FROM catalog_marker").update();
    UUID coder = seedWithoutCategory("Backfilled AI coding assistant");

    BackfillCategories.Result first = backfill.runIfChanged();

    assertThat(first).isNotNull();
    assertThat(first.resources()).isGreaterThanOrEqualTo(1);
    assertThat(first.categorized()).isGreaterThanOrEqualTo(1);
    assertThat(categoryLabels(coder)).isEqualTo(1);
    assertThat(store.marker(BackfillCategories.MARKER))
        .contains(catalog.getFingerprint() + "/" + store.lastMigration());

    // Same words: nothing to do. Labels removed by hand stay removed until the words change.
    jdbc.sql("DELETE FROM resource_label WHERE resource_id = :id").param("id", coder).update();
    assertThat(backfill.runIfChanged()).isNull();
    assertThat(categoryLabels(coder)).isZero();

    store.mark(BackfillCategories.MARKER, "older-words");
    assertThat(backfill.runIfChanged()).isNotNull();
    assertThat(categoryLabels(coder)).isEqualTo(1);
  }
}
