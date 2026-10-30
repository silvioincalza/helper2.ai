package ai.helper2.identity.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The app_user table: the provider's subject, a display name and the last sign-in, nothing else. */
@Repository
public class Users {

    private final JdbcClient jdbc;

    Users(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The local id of (provider, subject), while that account exists. */
    public Optional<UUID> find(String provider, String subject) {
        return jdbc.sql("SELECT id FROM app_user WHERE provider = :p AND subject = :s")
            .param("p", provider).param("s", subject).query(UUID.class).optional();
    }

    /** Upsert by (provider, subject), on sign-in: refreshes the display name and the last sign-in. */
    public UUID signedIn(String provider, String subject, String displayName) {
        return jdbc.sql("""
                INSERT INTO app_user (id, provider, subject, display_name) VALUES (gen_random_uuid(), :p, :s, :n)
                ON CONFLICT (provider, subject) DO UPDATE SET display_name = coalesce(EXCLUDED.display_name, app_user.display_name),
                    last_signed_in_at = now()
                RETURNING id
                """).param("p", provider).param("s", subject).param("n", displayName).query(UUID.class).single();
    }

    /** Deletes the account; its bookmarks go with it, its issue reports stay without a reporter (foreign keys). */
    public void delete(UUID userId) {
        jdbc.sql("DELETE FROM app_user WHERE id = :id").param("id", userId).update();
    }

    /** Deletes the accounts whose last sign-in is before [cutoff], as {@link #delete} does; returns how many. */
    public int deleteSignedInLastBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM app_user WHERE last_signed_in_at < :cutoff").param("cutoff", Timestamp.from(cutoff)).update();
    }
}
