package ai.helper2.platform;

import ai.helper2.shared.Refused;
import ai.helper2.shared.UnitOfWork;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

public final class TransactionalUnitOfWork implements UnitOfWork {

  private final TransactionTemplate template;

  public TransactionalUnitOfWork(PlatformTransactionManager transactions) {
    this.template = new TransactionTemplate(transactions);
  }

  @Override
  public <T> T run(Supplier<T> work) {
    return template.execute(status -> undoWhenRefused(work.get(), status));
  }

  private static <T> T undoWhenRefused(T result, TransactionStatus status) {
    if (result instanceof Refused && status.isNewTransaction()) {
      status.setRollbackOnly();
    }
    return result;
  }
}
