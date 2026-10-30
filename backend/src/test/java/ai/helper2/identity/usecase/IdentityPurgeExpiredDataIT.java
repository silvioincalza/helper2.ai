package ai.helper2.identity.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.identity.internal.AuditLog;
import ai.helper2.identity.internal.Users;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Identity's retention rules on the real schema; the clock is fixed in 2000, before any row other tests write. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdentityPurgeExpiredDataIT {

    static final Instant NOW = Instant.parse("2000-07-01T00:00:00Z");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Users users;

    @Autowired
    AuditLog audit;

    /** The defaults of application.yaml: 24 months for both. */
    PurgeExpiredData purge() {
        return new PurgeExpiredData(users, audit, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(730), Duration.ofDays(730));
    }

    UUID account(Instant lastSignedIn) {
        UUID id = users.signedIn("test", "retention-" + UUID.randomUUID(), "Someone");
        jdbc.sql("UPDATE app_user SET last_signed_in_at = :at WHERE id = :id").param("at", Timestamp.from(lastSignedIn)).param("id", id).update();
        jdbc.sql("INSERT INTO bookmark (user_id, resource_id, title, url) VALUES (:u, gen_random_uuid(), 'T', 'https://example.com')")
            .param("u", id).update();
        return id;
    }

    long count(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }

    UUID auditEntry(Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO admin_audit (id, admin_id, action, target_id, at, outcome) VALUES (:id, '1', 'START_SCOUT_RUN', NULL, :at, 'STARTED')")
            .param("id", id).param("at", Timestamp.from(at)).update();
        return id;
    }

    @Test
    void accountsWithoutSignInForTwoYearsGoWithTheirBookmarks() {
        UUID inactive = account(NOW.minus(Duration.ofDays(731)));
        UUID active = account(NOW.minus(Duration.ofDays(729)));

        assertThat(purge().purge().accounts()).isEqualTo(1);

        assertThat(count("SELECT count(*) FROM app_user WHERE id = :id", inactive)).isZero();
        assertThat(count("SELECT count(*) FROM bookmark WHERE user_id = :id", inactive)).isZero();
        assertThat(count("SELECT count(*) FROM app_user WHERE id = :id", active)).isOne();
        assertThat(count("SELECT count(*) FROM bookmark WHERE user_id = :id", active)).isOne();
    }

    @Test
    void auditEntriesOlderThanTwoYearsAreDeleted() {
        UUID old = auditEntry(NOW.minus(Duration.ofDays(731)));
        UUID recent = auditEntry(NOW.minus(Duration.ofDays(729)));

        purge().purge();

        assertThat(count("SELECT count(*) FROM admin_audit WHERE id = :id", old)).isZero();
        assertThat(count("SELECT count(*) FROM admin_audit WHERE id = :id", recent)).isOne();
    }

    @Test
    void auditEntriesCannotBeChanged() {
        UUID entry = auditEntry(NOW);
        assertThatThrownBy(() -> jdbc.sql("UPDATE admin_audit SET outcome = 'BUSY' WHERE id = :id").param("id", entry).update())
            .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }
}
