package ai.helper2.platform;

import ai.helper2.shared.UnitOfWork;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
class UnitOfWorkConfiguration {

  @Bean
  UnitOfWork unitOfWork(PlatformTransactionManager transactions) {
    return new TransactionalUnitOfWork(transactions);
  }
}
