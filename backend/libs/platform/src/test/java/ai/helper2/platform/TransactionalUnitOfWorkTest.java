package ai.helper2.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.shared.UnitOfWork;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class TransactionalUnitOfWorkTest {

  private final RecordingTransactions transactions = new RecordingTransactions();
  private final UnitOfWork unitOfWork = new TransactionalUnitOfWork(transactions);

  @Test
  void commitsTheWorkAndReturnsItsResult() {
    String result = unitOfWork.run(() -> "done");

    assertThat(result).isEqualTo("done");
    assertThat(transactions.events).containsExactly("begin", "commit");
  }

  @Test
  void rollsBackAndRethrowsWhenTheWorkFails() {
    IllegalStateException failure = new IllegalStateException("no");

    assertThatThrownBy(() ->
            unitOfWork.run(() -> {
              throw failure;
            }))
        .isSameAs(failure);
    assertThat(transactions.events).containsExactly("begin", "rollback");
  }

  @Test
  void commitsWorkWithoutAResult() {
    List<String> done = new ArrayList<>();

    unitOfWork.runWithoutResult(() -> done.add("work"));

    assertThat(done).containsExactly("work");
    assertThat(transactions.events).containsExactly("begin", "commit");
  }

  @Test
  void rollsBackWorkWithoutAResultWhenItFails() {
    assertThatThrownBy(() ->
            unitOfWork.runWithoutResult(() -> {
              throw new IllegalStateException("no");
            }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(transactions.events).containsExactly("begin", "rollback");
  }

  @Test
  void opensARequiredReadWriteTransaction() {
    unitOfWork.run(() -> "done");

    assertThat(transactions.definitions)
        .singleElement()
        .satisfies(definition -> {
          assertThat(definition.getPropagationBehavior())
              .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
          assertThat(definition.getIsolationLevel())
              .isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
          assertThat(definition.isReadOnly()).isFalse();
        });
  }

  private static final class RecordingTransactions extends AbstractPlatformTransactionManager {

    private final List<String> events = new ArrayList<>();
    private final List<TransactionDefinition> definitions = new ArrayList<>();

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      definitions.add(definition);
      events.add("begin");
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      events.add("commit");
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      events.add("rollback");
    }
  }
}
