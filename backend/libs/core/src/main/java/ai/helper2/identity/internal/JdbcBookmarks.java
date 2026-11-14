package ai.helper2.identity.internal;

import ai.helper2.identity.Bookmarks;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcBookmarks implements Bookmarks {

  private final JdbcClient jdbc;

  JdbcBookmarks(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void lockForChange(UUID userId) {
    jdbc.sql("SELECT id FROM app_user WHERE id = :u FOR UPDATE")
        .param("u", userId)
        .query()
        .listOfRows();
  }

  @Override
  public List<Bookmark> list(UUID userId) {
    return jdbc.sql(
            "SELECT resource_id, title, url, type, saved_at FROM bookmark WHERE user_id = :u ORDER BY saved_at DESC")
        .param("u", userId)
        .query((rs, i) ->
            new Bookmark(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                URI.create(rs.getString(3)),
                rs.getString(4),
                rs.getTimestamp(5).toInstant()))
        .list();
  }

  @Override
  public void addMissing(UUID userId, List<Bookmark> items) {
    for (Bookmark b : items) {
      jdbc.sql(
              """
              INSERT INTO bookmark (user_id, resource_id, title, url, type) VALUES (:u, :r, :t, :url, :type)
              ON CONFLICT (user_id, resource_id) DO NOTHING
              """)
          .param("u", userId)
          .param("r", b.resourceId())
          .param("t", b.title())
          .param("url", b.url().toString())
          .param("type", b.type())
          .update();
    }
  }

  @Override
  public long count(UUID userId) {
    return jdbc.sql("SELECT count(*) FROM bookmark WHERE user_id = :u")
        .param("u", userId)
        .query(Long.class)
        .single();
  }

  @Override
  public void remove(UUID userId, UUID resourceId) {
    jdbc.sql("DELETE FROM bookmark WHERE user_id = :u AND resource_id = :r")
        .param("u", userId)
        .param("r", resourceId)
        .update();
  }
}
