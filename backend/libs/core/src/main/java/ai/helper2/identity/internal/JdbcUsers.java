package ai.helper2.identity.internal;

import ai.helper2.identity.Users;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcUsers implements Users {

  private final JdbcClient jdbc;

  JdbcUsers(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<UUID> find(String provider, String subject) {
    return jdbc.sql("SELECT id FROM app_user WHERE provider = :p AND subject = :s")
        .param("p", provider)
        .param("s", subject)
        .query(UUID.class)
        .optional();
  }

  @Override
  public UUID signedIn(String provider, String subject, String displayName) {
    return jdbc.sql(
            """
            INSERT INTO app_user (id, provider, subject, display_name) VALUES (gen_random_uuid(), :p, :s, :n)
            ON CONFLICT (provider, subject) DO UPDATE SET display_name = coalesce(EXCLUDED.display_name, app_user.display_name),
                last_signed_in_at = now()
            RETURNING id
            """)
        .param("p", provider)
        .param("s", subject)
        .param("n", displayName)
        .query(UUID.class)
        .single();
  }

  @Override
  public Optional<Preferences> preferences(UUID userId) {
    return jdbc.sql("SELECT radar_motion FROM app_user WHERE id = :id")
        .param("id", userId)
        .query((rs, n) -> new Preferences(rs.getBoolean("radar_motion")))
        .optional();
  }

  @Override
  public Optional<Preferences> changeRadarMotion(UUID userId, boolean radarMotion) {
    return jdbc.sql("UPDATE app_user SET radar_motion = :m WHERE id = :id RETURNING radar_motion")
        .param("m", radarMotion)
        .param("id", userId)
        .query((rs, n) -> new Preferences(rs.getBoolean("radar_motion")))
        .optional();
  }

  @Override
  public void delete(UUID userId) {
    jdbc.sql("DELETE FROM app_user WHERE id = :id").param("id", userId).update();
  }

  @Override
  public int deleteSignedInLastBefore(Instant cutoff) {
    return jdbc.sql("DELETE FROM app_user WHERE last_signed_in_at < :cutoff")
        .param("cutoff", Timestamp.from(cutoff))
        .update();
  }
}
