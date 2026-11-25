package ai.searchavo.admin.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.admin.config.AdminUseCaseConfiguration.TransactionalChangeRuntimeSetting;
import ai.searchavo.admin.config.AdminUseCaseConfiguration.TransactionalResetRuntimeSetting;
import ai.searchavo.admin.usecase.ChangeRuntimeSetting;
import ai.searchavo.admin.usecase.GetRuntimeSettings;
import ai.searchavo.admin.usecase.ResetRuntimeSetting;
import ai.searchavo.platform.AuditTrail;
import ai.searchavo.platform.settings.RuntimeSetting;
import ai.searchavo.platform.settings.RuntimeSettingStore;
import ai.searchavo.platform.settings.RuntimeSettings;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SettingsReloadAfterCommitTest {
  private static final String SECRET_DETAIL = "password=hunter2 value=4242";
  private static final RuntimeSetting SETTING = RuntimeSetting.GUIDE_DAILY_MODEL_CALLS;
  private static final AuditTrail NO_AUDIT = (adminId, action, targetId, outcome) -> {};

  private final Clock clock = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC);
  private final UnreadableAfterAppendStore store = new UnreadableAfterAppendStore();
  private final RuntimeSettings runtime = new RuntimeSettings(store, clock, Duration.ofMinutes(1));
  private final GetRuntimeSettings settings = new GetRuntimeSettings(store, runtime);
  private final Logger logger = (Logger) LoggerFactory.getLogger(SettingsReloadAfterCommit.class);
  private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

  @BeforeEach
  void openATransactionAndListenToTheLog() {
    TransactionSynchronizationManager.initSynchronization();
    logged.start();
    logger.addAppender(logged);
  }

  @AfterEach
  void closeTheTransactionAndStopListening() {
    logger.detachAppender(logged);
    TransactionSynchronizationManager.clearSynchronization();
  }

  @Test
  void aChangeWhoseReloadFailsAfterTheCommitStaysChangedAndTheFailureIsLogged() {
    var change = new TransactionalChangeRuntimeSetting(store, settings, runtime, NO_AUDIT, clock);

    ChangeRuntimeSetting.Outcome outcome = change.change("admin", SETTING, 7, 0, null);
    commit();

    assertThat(outcome).isInstanceOf(ChangeRuntimeSetting.Outcome.Changed.class);
    assertTheFailureIsLoggedByItsClassOnly();
  }

  @Test
  void aResetWhoseReloadFailsAfterTheCommitStaysResetAndTheFailureIsLogged() {
    var reset = new TransactionalResetRuntimeSetting(store, settings, runtime, NO_AUDIT, clock);

    ResetRuntimeSetting.Outcome outcome = reset.reset("admin", SETTING, 0, null);
    commit();

    assertThat(outcome).isInstanceOf(ResetRuntimeSetting.Outcome.Reset.class);
    assertTheFailureIsLoggedByItsClassOnly();
  }

  private static void commit() {
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
  }

  private void assertTheFailureIsLoggedByItsClassOnly() {
    assertThat(logged.list).hasSize(1);
    ILoggingEvent event = logged.list.getFirst();
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getFormattedMessage())
        .contains("IllegalStateException")
        .doesNotContain(SECRET_DETAIL);
    assertThat(event.getThrowableProxy()).isNull();
  }

  private static final class UnreadableAfterAppendStore implements RuntimeSettingStore {
    private final List<Change> changes = new ArrayList<>();

    @Override
    public long version() {
      throw new IllegalStateException(SECRET_DETAIL);
    }

    @Override
    public Map<String, Change> newest() {
      throw new IllegalStateException(SECRET_DETAIL);
    }

    @Override
    public List<Change> history(String name, int limit) {
      return changes.reversed().stream().filter(c -> c.name().equals(name)).limit(limit).toList();
    }

    @Override
    public Change append(
        String name,
        @Nullable Long value,
        long expectedVersion,
        String changedBy,
        Instant at,
        @Nullable String reason) {
      Change change = new Change(changes.size() + 1L, name, value, changedBy, at, reason);
      changes.add(change);
      return change;
    }

    @Override
    public int deleteBefore(Instant cutoff) {
      return 0;
    }
  }
}
