package ai.helper2.catalog.internal;

import org.springframework.jdbc.core.simple.JdbcClient;

final class KnownTargetLock {

  private static final String KEY = "hashtext('known_target')";

  private KnownTargetLock() {}

  static void forChange(JdbcClient jdbc) {
    jdbc.sql("SELECT pg_advisory_xact_lock(" + KEY + ")").query().singleRow();
  }

  static void forUse(JdbcClient jdbc) {
    jdbc.sql("SELECT pg_advisory_xact_lock_shared(" + KEY + ")").query().singleRow();
  }
}
