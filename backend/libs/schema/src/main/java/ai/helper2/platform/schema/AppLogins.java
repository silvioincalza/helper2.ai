package ai.helper2.platform.schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.postgresql.PGConnection;

public final class AppLogins {
  public static final Set<String> ROLES = Set.of("h2_public", "h2_backoffice");

  private AppLogins() {}

  public static void open(DataSource owner, Map<String, String> passwords) throws SQLException {
    open(owner, passwords, Map.of());
  }

  public static void open(
      DataSource owner, Map<String, String> passwords, Map<String, Duration> statementTimeouts)
      throws SQLException {
    Map<String, Duration> timeouts = checked(statementTimeouts);
    if (passwords == null) {
      throw new IllegalArgumentException(
          "No passwords for " + ROLES.stream().sorted().toList() + " (helper2.schema.logins)");
    }
    if (!passwords.keySet().equals(ROLES)) {
      throw new IllegalArgumentException(
          "Passwords are needed for exactly "
              + ROLES.stream().sorted().toList()
              + ", got "
              + passwords.keySet().stream().sorted().toList());
    }
    try (Connection connection = owner.getConnection();
        Statement statement = connection.createStatement()) {
      PGConnection postgres = connection.unwrap(PGConnection.class);
      for (String role : ROLES.stream().sorted().toList()) {
        String password = passwords.get(role);
        if (password == null || password.isBlank()) {
          throw new IllegalArgumentException("No password for " + role);
        }
        statement.execute("ALTER ROLE " + role + " LOGIN");
        postgres.alterUserPassword(role, password.toCharArray(), null);
        statement.execute(statementTimeoutOf(role, timeouts.get(role)));
      }
    }
  }

  private static Map<String, Duration> checked(Map<String, Duration> statementTimeouts) {
    Map<String, Duration> timeouts = statementTimeouts == null ? Map.of() : statementTimeouts;
    if (!ROLES.containsAll(timeouts.keySet())) {
      throw new IllegalArgumentException(
          "Statement timeouts are for "
              + ROLES.stream().sorted().toList()
              + " only, got "
              + timeouts.keySet().stream().sorted().toList());
    }
    timeouts.forEach((role, timeout) -> {
      if (timeout == null || timeout.isNegative() || timeout.isZero()) {
        throw new IllegalArgumentException(
            "The statement timeout of " + role + " must be positive");
      }
    });
    return timeouts;
  }

  private static String statementTimeoutOf(String role, Duration timeout) {
    return timeout == null
        ? "ALTER ROLE " + role + " RESET statement_timeout"
        : "ALTER ROLE " + role + " SET statement_timeout = " + timeout.toMillis();
  }
}
