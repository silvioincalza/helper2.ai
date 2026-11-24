package ai.searchavo.api;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.catalog.AwesomeListIndex;
import ai.searchavo.catalog.CatalogRetention;
import ai.searchavo.catalog.CuratedFields;
import ai.searchavo.catalog.EmbeddingStore;
import ai.searchavo.catalog.RelationshipTargets;
import ai.searchavo.catalog.ReviewDecisions;
import ai.searchavo.catalog.ReviewEvidence;
import ai.searchavo.catalog.enrichment.EnrichmentCandidates;
import ai.searchavo.catalog.usecase.AddCuratedResource;
import ai.searchavo.catalog.usecase.BackfillCategories;
import ai.searchavo.catalog.usecase.ChangeRelationshipTarget;
import ai.searchavo.catalog.usecase.CurateField;
import ai.searchavo.catalog.usecase.ForgetWithdrawnRecords;
import ai.searchavo.catalog.usecase.GetResourceDetail;
import ai.searchavo.catalog.usecase.GetReviewItem;
import ai.searchavo.catalog.usecase.IndexAwesomeLists;
import ai.searchavo.catalog.usecase.IndexEmbeddings;
import ai.searchavo.catalog.usecase.IndexResources;
import ai.searchavo.catalog.usecase.ListReviewItems;
import ai.searchavo.catalog.usecase.ListWellKnownResources;
import ai.searchavo.catalog.usecase.LookUpResourceTitles;
import ai.searchavo.catalog.usecase.PurgeExpiredData;
import ai.searchavo.catalog.usecase.ResolveReviewItem;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

class NoBackOfficeCatalogIT extends ApiTestBase {

  @Autowired ApplicationContext context;

  @Test
  void thePublicAppHasNoBackOfficeUseCaseOrAdapterOfTheCatalog() {
    List<Class<?>> backOffice =
        List.of(
            AddCuratedResource.class,
            BackfillCategories.class,
            ChangeRelationshipTarget.class,
            CurateField.class,
            ForgetWithdrawnRecords.class,
            GetReviewItem.class,
            IndexAwesomeLists.class,
            IndexEmbeddings.class,
            IndexResources.class,
            ListReviewItems.class,
            ListWellKnownResources.class,
            LookUpResourceTitles.class,
            PurgeExpiredData.class,
            ResolveReviewItem.class,
            AwesomeListIndex.class,
            CatalogRetention.class,
            CuratedFields.class,
            EmbeddingStore.class,
            RelationshipTargets.class,
            ReviewDecisions.class,
            ReviewEvidence.class,
            EnrichmentCandidates.class);

    assertThat(backOffice)
        .allSatisfy(
            type -> assertThat(context.getBeanNamesForType(type)).as(type.getName()).isEmpty());
    assertThat(context.getBeanNamesForType(GetResourceDetail.class)).hasSize(1);
  }
}
