package ai.helper2.catalog.config;

import ai.helper2.catalog.AwesomeListEntries;
import ai.helper2.catalog.CategoryAssignments;
import ai.helper2.catalog.CategoryInputs;
import ai.helper2.catalog.EnrichmentFacts;
import ai.helper2.catalog.IssueReports;
import ai.helper2.catalog.ResourceCards;
import ai.helper2.catalog.Resources;
import ai.helper2.catalog.usecase.GetResourceDetail;
import ai.helper2.catalog.usecase.ListIndexableResources;
import ai.helper2.catalog.usecase.ListNewestIndexable;
import ai.helper2.catalog.usecase.ListRecentResources;
import ai.helper2.catalog.usecase.ListUpcomingEvents;
import ai.helper2.catalog.usecase.RefreshCategories;
import ai.helper2.catalog.usecase.ReportIssue;
import ai.helper2.search.DescribedSource;
import ai.helper2.search.ShadowLibraries;
import ai.helper2.shared.CategoryMatcher;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

@Configuration(proxyBeanMethods = false)
class CatalogUseCaseConfiguration {

  @Bean
  ResourceCards resourceCards(
      Resources resources, List<DescribedSource> sources, ShadowLibraries shadowLibraries) {
    return new ResourceCards(resources, sources, shadowLibraries);
  }

  @Bean
  GetResourceDetail getResourceDetail(
      Resources resources,
      EnrichmentFacts enrichment,
      ResourceCards cards,
      AwesomeListEntries listEntries) {
    return new GetResourceDetail(resources, enrichment, cards, listEntries);
  }

  @Bean
  ListIndexableResources listIndexableResources(Resources resources) {
    return new ListIndexableResources(resources);
  }

  @Bean
  ListNewestIndexable listNewestIndexable(Resources resources, ResourceCards cards) {
    return new ListNewestIndexable(resources, cards);
  }

  @Bean
  ListRecentResources listRecentResources(Resources resources, ResourceCards cards) {
    return new ListRecentResources(resources, cards);
  }

  @Bean
  ListUpcomingEvents listUpcomingEvents(Resources resources, ResourceCards cards) {
    return new ListUpcomingEvents(resources, cards);
  }

  @Bean
  ReportIssue reportIssue(IssueReports reports) {
    return new ReportIssue(reports);
  }

  @Bean
  RefreshCategories refreshCategories(
      CategoryInputs inputs, CategoryAssignments assignments, CategoryMatcher matcher) {
    return new TransactionalRefreshCategories(inputs, assignments, matcher);
  }

  static class TransactionalRefreshCategories extends RefreshCategories {

    TransactionalRefreshCategories(
        CategoryInputs inputs, CategoryAssignments assignments, CategoryMatcher matcher) {
      super(inputs, assignments, matcher);
    }

    @Override
    @Transactional
    public int refreshCategories(Collection<UUID> resourceIds) {
      return super.refreshCategories(resourceIds);
    }
  }
}
