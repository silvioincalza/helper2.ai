package ai.helper2.identity.internal;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The bookmark table. The rules (valid URLs, the per-account cap) are the use cases' (identity.usecase). */
@Repository
public class Bookmarks {

    public record Bookmark(UUID resourceId, String title, URI url, String type, Instant savedAt) {
    }

    private final JdbcClient jdbc;

    Bookmarks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Newest first. */
    public List<Bookmark> list(UUID userId) {
        return jdbc.sql("SELECT resource_id, title, url, type, saved_at FROM bookmark WHERE user_id = :u ORDER BY saved_at DESC")
            .param("u", userId)
            .query((rs, i) -> new Bookmark(rs.getObject(1, UUID.class), rs.getString(2), URI.create(rs.getString(3)),
                rs.getString(4), rs.getTimestamp(5).toInstant()))
            .list();
    }

    /** Inserts each resource the account has not saved yet; one it has keeps its first savedAt. */
    public void addMissing(UUID userId, List<Bookmark> items) {
        for (Bookmark b : items) {
            jdbc.sql("""
                    INSERT INTO bookmark (user_id, resource_id, title, url, type) VALUES (:u, :r, :t, :url, :type)
                    ON CONFLICT (user_id, resource_id) DO NOTHING
                    """).param("u", userId).param("r", b.resourceId()).param("t", b.title())
                .param("url", b.url().toString()).param("type", b.type()).update();
        }
    }

    public long count(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM bookmark WHERE user_id = :u").param("u", userId).query(Long.class).single();
    }

    public void remove(UUID userId, UUID resourceId) {
        jdbc.sql("DELETE FROM bookmark WHERE user_id = :u AND resource_id = :r").param("u", userId).param("r", resourceId).update();
    }
}
