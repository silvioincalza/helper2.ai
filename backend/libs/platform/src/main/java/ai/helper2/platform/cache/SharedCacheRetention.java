package ai.helper2.platform.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class SharedCacheRetention {

  private static final Logger log = LoggerFactory.getLogger(SharedCacheRetention.class);

  private final JdbcClient jdbc;

  public SharedCacheRetention(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public int purge() {
    int deleted = jdbc.sql("DELETE FROM ai_cache WHERE expires_at <= now()").update();
    log.info(
        "shared cache retention: {} expired entr{} deleted", deleted, deleted == 1 ? "y" : "ies");
    return deleted;
  }
}
