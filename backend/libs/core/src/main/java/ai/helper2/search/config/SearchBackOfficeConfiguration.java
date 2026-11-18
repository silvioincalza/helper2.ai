package ai.helper2.search.config;

import ai.helper2.platform.AuditTrail;
import ai.helper2.search.DescribedSource;
import ai.helper2.search.ShadowLibraries;
import ai.helper2.search.ShadowLibraryStore;
import ai.helper2.search.UnitOfWork;
import ai.helper2.search.usecase.ManageShadowLibraries;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.NamedInterface;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@NamedInterface("backoffice")
public class SearchBackOfficeConfiguration {

  private static final Logger log = LoggerFactory.getLogger(SearchBackOfficeConfiguration.class);

  @Bean
  UnitOfWork searchUnitOfWork(PlatformTransactionManager transactions) {
    TransactionTemplate template = new TransactionTemplate(transactions);
    return new UnitOfWork() {
      @Override
      public <T> T run(java.util.function.Supplier<T> work) {
        return template.execute(status -> work.get());
      }
    };
  }

  @Bean
  ManageShadowLibraries manageShadowLibraries(
      ShadowLibraryStore store,
      ShadowLibraries libraries,
      AuditTrail audit,
      UnitOfWork searchUnitOfWork,
      Clock clock,
      List<DescribedSource> sources) {
    ManageShadowLibraries manage =
        new ManageShadowLibraries(
            store, libraries, audit, searchUnitOfWork, clock, hostsOf(sources));
    warnOfNamesThatHideASource(manage);
    return manage;
  }

  private static void warnOfNamesThatHideASource(ManageShadowLibraries manage) {
    try {
      manage
          .namesThatHideASource()
          .forEach(name ->
              log.warn(
                  "shadow library name {} is a label of a source's host: that source's results"
                      + " are hidden",
                  name));
    } catch (RuntimeException e) {
      log.warn(
          "shadow library names not checked against the sources: {}", e.getClass().getSimpleName());
    }
  }

  private static List<String> hostsOf(List<DescribedSource> sources) {
    return sources.stream()
        .map(s -> s.getDescriptor().getHomepage().getHost())
        .filter(Objects::nonNull)
        .toList();
  }
}
