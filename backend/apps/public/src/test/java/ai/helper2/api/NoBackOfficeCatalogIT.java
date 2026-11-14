package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.catalog.AwesomeListIndex;
import ai.helper2.catalog.CatalogRetention;
import ai.helper2.catalog.CuratedFields;
import ai.helper2.catalog.EmbeddingStore;
import ai.helper2.catalog.RelationshipTargets;
import ai.helper2.catalog.ReviewDecisions;
import ai.helper2.catalog.ReviewEvidence;
import ai.helper2.catalog.enrichment.EnrichmentCandidates;
import ai.helper2.catalog.usecase.AddCuratedResource;
import ai.helper2.catalog.usecase.BackfillCategories;
import ai.helper2.catalog.usecase.ChangeRelationshipTarget;
import ai.helper2.catalog.usecase.CurateField;
import ai.helper2.catalog.usecase.ForgetWithdrawnRecords;
import ai.helper2.catalog.usecase.GetResourceDetail;
import ai.helper2.catalog.usecase.GetReviewItem;
import ai.helper2.catalog.usecase.IndexAwesomeLists;
import ai.helper2.catalog.usecase.IndexEmbeddings;
import ai.helper2.catalog.usecase.IndexResources;
import ai.helper2.catalog.usecase.ListReviewItems;
import ai.helper2.catalog.usecase.ListWellKnownResources;
import ai.helper2.catalog.usecase.LookUpResourceTitles;
import ai.helper2.catalog.usecase.PurgeExpiredData;
import ai.helper2.catalog.usecase.ResolveReviewItem;
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
