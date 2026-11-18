package ai.helper2.search.config;

import ai.helper2.platform.AuditTrail;
import ai.helper2.search.ShadowLibraries;
import ai.helper2.search.ShadowLibraryStore;
import ai.helper2.search.UnitOfWork;
import ai.helper2.search.usecase.ManageShadowLibraries;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.NamedInterface;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@NamedInterface("backoffice")
public class SearchBackOfficeConfiguration {

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
      Clock clock) {
    return new ManageShadowLibraries(store, libraries, audit, searchUnitOfWork, clock);
  }
}
