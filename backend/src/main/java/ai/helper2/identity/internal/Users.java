package ai.helper2.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class Users {

    private final JdbcClient jdbc;

    Users(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The local id of (provider, subject), when that user has signed in before. */
    public Optional<UUID> find(String provider, String subject) {
        return jdbc.sql("SELECT id FROM app_user WHERE provider = :p AND subject = :s")
            .param("p", provider).param("s", subject).query(UUID.class).optional();
    }

    /** Upsert by (provider, subject), on sign-in. Stores no e-mail: only what is needed to show a name. */
    public UUID ensure(String provider, String subject, String displayName) {
        return jdbc.sql("""
                INSERT INTO app_user (id, provider, subject, display_name) VALUES (gen_random_uuid(), :p, :s, :n)
                ON CONFLICT (provider, subject) DO UPDATE SET display_name = coalesce(EXCLUDED.display_name, app_user.display_name)
                RETURNING id
                """).param("p", provider).param("s", subject).param("n", displayName).query(UUID.class).single();
    }
}
