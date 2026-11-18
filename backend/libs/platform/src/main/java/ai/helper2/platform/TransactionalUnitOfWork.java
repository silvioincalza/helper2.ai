package ai.helper2.platform;

import ai.helper2.shared.UnitOfWork;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class TransactionalUnitOfWork implements UnitOfWork {

  private final TransactionTemplate template;

  public TransactionalUnitOfWork(PlatformTransactionManager transactions) {
    this.template = new TransactionTemplate(transactions);
  }

  @Override
  public <T> T run(Supplier<T> work) {
    return template.execute(status -> work.get());
  }
}
