package ai.searchavo.api;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.catalog.CatalogRetention;
import ai.searchavo.catalog.CuratedFields;
import ai.searchavo.catalog.ReviewEvidence;
import ai.searchavo.catalog.enrichment.EnrichmentCandidates;
import ai.searchavo.catalog.usecase.CurateField;
import ai.searchavo.catalog.usecase.GetReviewItem;
import ai.searchavo.catalog.usecase.ResolveReviewItem;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

class BackOfficeCatalogIT extends ApiTestBase {

  @Autowired ApplicationContext context;

  @Test
  void theBackOfficeImportsTheCatalogsAdminUseCasesAndAdapters() {
    List<Class<?>> backOffice =
        List.of(
            CurateField.class,
            GetReviewItem.class,
            ResolveReviewItem.class,
            CatalogRetention.class,
            CuratedFields.class,
            ReviewEvidence.class,
            EnrichmentCandidates.class);

    assertThat(backOffice)
        .allSatisfy(
            type -> assertThat(context.getBeanNamesForType(type)).as(type.getName()).hasSize(1));
  }
}
