package ai.helper2.identity.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.identity.Users;
import ai.helper2.identity.usecase.PurgeInactiveAccounts;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccountRetentionIT {

  static final Instant NOW = Instant.parse("2000-07-01T00:00:00Z");

  @Autowired JdbcClient jdbc;

  @Autowired Users users;

  PurgeInactiveAccounts purge() {
    return new PurgeInactiveAccounts(users, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(730));
  }

  UUID account(Instant lastSignedIn) {
    UUID id = users.signedIn("test", "retention-" + UUID.randomUUID(), "Someone");
    jdbc.sql("UPDATE app_user SET last_signed_in_at = :at WHERE id = :id")
        .param("at", Timestamp.from(lastSignedIn))
        .param("id", id)
        .update();
    jdbc.sql(
            "INSERT INTO bookmark (user_id, resource_id, title, url) VALUES (:u, gen_random_uuid(), 'T', 'https://example.com')")
        .param("u", id)
        .update();
    return id;
  }

  long count(String sql, UUID id) {
    return jdbc.sql(sql).param("id", id).query(Long.class).single();
  }

  @Test
  void accountsWithoutSignInForTwoYearsGoWithTheirBookmarks() {
    UUID inactive = account(NOW.minus(Duration.ofDays(731)));
    UUID active = account(NOW.minus(Duration.ofDays(729)));

    assertThat(purge().purge()).isEqualTo(1);

    assertThat(count("SELECT count(*) FROM app_user WHERE id = :id", inactive)).isZero();
    assertThat(count("SELECT count(*) FROM bookmark WHERE user_id = :id", inactive)).isZero();
    assertThat(count("SELECT count(*) FROM app_user WHERE id = :id", active)).isOne();
    assertThat(count("SELECT count(*) FROM bookmark WHERE user_id = :id", active)).isOne();
  }

  @Autowired org.springframework.context.ApplicationContext context;

  @Test
  void thePublicAppHasNoAuditPurge() {
    assertThat(
            context.getBeanNamesForType(ai.helper2.identity.usecase.PurgeExpiredAuditEntries.class))
        .isEmpty();
  }
}
