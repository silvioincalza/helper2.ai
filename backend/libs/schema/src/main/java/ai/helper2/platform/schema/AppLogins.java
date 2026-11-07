package ai.helper2.platform.schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.postgresql.PGConnection;

/**
 * Opens the login of each app's role (ADR 0070): V23 creates the roles without one, the migrate
 * step gives them LOGIN and their password, as the owner. The driver hashes the password (SCRAM)
 * before it is sent: the plaintext never reaches the server. The SCRAM verifier does, in an ALTER
 * ROLE statement, so it can appear in the server log when log_statement is ddl or all. Run on every
 * deploy: a new password in the env Secret replaces the old one.
 */
public final class AppLogins {

  /** The roles V23 creates; nothing else gets a login here. */
  public static final Set<String> ROLES = Set.of("h2_public", "h2_backoffice");

  private AppLogins() {}

  /** Each role of {@link #ROLES} with its password; every one of them is required. */
  public static void open(DataSource owner, Map<String, String> passwords) throws SQLException {
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
        // The role names are the fixed ones above, never input: safe to name in the statement.
        statement.execute("ALTER ROLE " + role + " LOGIN");
        postgres.alterUserPassword(role, password.toCharArray(), null);
      }
    }
  }
}
