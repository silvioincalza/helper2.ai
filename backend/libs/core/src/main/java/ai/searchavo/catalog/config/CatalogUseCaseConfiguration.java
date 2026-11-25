package ai.searchavo.catalog.config;

import ai.searchavo.catalog.AwesomeListEntries;
import ai.searchavo.catalog.CategoryAssignments;
import ai.searchavo.catalog.CategoryInputs;
import ai.searchavo.catalog.EnrichmentFacts;
import ai.searchavo.catalog.IssueReports;
import ai.searchavo.catalog.ResourceCards;
import ai.searchavo.catalog.ResourceRatings;
import ai.searchavo.catalog.Resources;
import ai.searchavo.catalog.usecase.GetResourceDetail;
import ai.searchavo.catalog.usecase.ListIndexableResources;
import ai.searchavo.catalog.usecase.ListNewestIndexable;
import ai.searchavo.catalog.usecase.ListRecentResources;
import ai.searchavo.catalog.usecase.ListUpcomingEvents;
import ai.searchavo.catalog.usecase.RefreshCategories;
import ai.searchavo.catalog.usecase.ReportIssue;
import ai.searchavo.search.DescribedSource;
import ai.searchavo.search.ShadowLibraries;
import ai.searchavo.shared.CategoryMatcher;
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
      AwesomeListEntries listEntries,
      ResourceRatings ratings) {
    return new GetResourceDetail(resources, enrichment, cards, listEntries, ratings);
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
