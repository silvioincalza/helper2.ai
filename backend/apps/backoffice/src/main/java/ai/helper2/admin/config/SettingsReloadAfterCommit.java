package ai.helper2.admin.config;

import ai.helper2.platform.settings.RuntimeSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

final class SettingsReloadAfterCommit {
  private static final Logger log = LoggerFactory.getLogger(SettingsReloadAfterCommit.class);

  private SettingsReloadAfterCommit() {}

  static void schedule(RuntimeSettings runtime) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      runtime.reloadIfChanged();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            reloadOrLeaveItToTheNextRefresh(runtime);
          }
        });
  }

  private static void reloadOrLeaveItToTheNextRefresh(RuntimeSettings runtime) {
    try {
      runtime.reloadIfChanged();
    } catch (RuntimeException e) {
      log.warn(
          "runtime settings not reloaded after a committed change, the next refresh applies it: {}",
          e.getClass().getSimpleName());
    }
  }
}
