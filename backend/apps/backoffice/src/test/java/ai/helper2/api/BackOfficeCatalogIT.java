package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.catalog.CatalogRetention;
import ai.helper2.catalog.CuratedFields;
import ai.helper2.catalog.ReviewEvidence;
import ai.helper2.catalog.enrichment.EnrichmentCandidates;
import ai.helper2.catalog.usecase.CurateField;
import ai.helper2.catalog.usecase.GetReviewItem;
import ai.helper2.catalog.usecase.ResolveReviewItem;
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
