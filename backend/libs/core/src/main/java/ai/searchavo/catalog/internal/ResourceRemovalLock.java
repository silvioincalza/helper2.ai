package ai.searchavo.catalog.internal;

import org.springframework.jdbc.core.simple.JdbcClient;

final class ResourceRemovalLock {

  private static final String KEY = "hashtext('removed_resource')";

  private ResourceRemovalLock() {}

  static void forRemoval(JdbcClient jdbc) {
    jdbc.sql("SELECT pg_advisory_xact_lock(" + KEY + ")").query().singleRow();
  }

  static void forWrite(JdbcClient jdbc) {
    jdbc.sql("SELECT pg_advisory_xact_lock_shared(" + KEY + ")").query().singleRow();
  }
}
