package ai.helper2.platform.schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.postgresql.PGConnection;

public final class MonitorLogin {
  public static final String ROLE = "h2_monitor";

  private MonitorLogin() {}

  public static void open(DataSource owner, String password) throws SQLException {
    if (password == null || password.isBlank()) {
      return;
    }
    try (Connection connection = owner.getConnection();
        Statement statement = connection.createStatement()) {
      createRoleIfMissing(statement);
      statement.execute(
          "ALTER ROLE "
              + ROLE
              + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS INHERIT"
              + " CONNECTION LIMIT 3");
      statement.execute("GRANT pg_monitor TO " + ROLE);
      statement.execute("GRANT CONNECT ON DATABASE " + connection.getCatalog() + " TO " + ROLE);
      statement.execute("ALTER ROLE " + ROLE + " SET statement_timeout = 10000");
      connection.unwrap(PGConnection.class).alterUserPassword(ROLE, password.toCharArray(), null);
    }
  }

  private static void createRoleIfMissing(Statement statement) throws SQLException {
    statement.execute(
        "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '"
            + ROLE
            + "') THEN CREATE ROLE "
            + ROLE
            + " NOLOGIN; END IF; END $$");
  }
}
