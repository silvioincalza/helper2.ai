package ai.searchavo.catalog.internal;

import ai.searchavo.catalog.DirectoryRating;
import ai.searchavo.catalog.ResourceRatings;
import ai.searchavo.catalog.StatedFigures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcResourceRatings implements ResourceRatings {

  private final JdbcClient jdbc;

  JdbcResourceRatings(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Map<UUID, List<DirectoryRating.Stated>> stated(Collection<UUID> resourceIds) {
    Map<UUID, List<DirectoryRating.Stated>> stated = new HashMap<>();
    if (resourceIds.isEmpty()) {
      return stated;
    }
    // UC-030 BR-010: figures outside their ranges are not kept, and are not read either.
    jdbc.sql(
            """
            SELECT resource_id, host, page_url, average, scale, votes, read_at
            FROM directory_rating
            WHERE resource_id = ANY(:ids) AND average IS NOT NULL
            ORDER BY host, page_url
            """)
        .param("ids", resourceIds.toArray(UUID[]::new))
        .query((rs, n) -> {
          UUID resource = rs.getObject("resource_id", UUID.class);
          String host = rs.getString("host");
          String page = rs.getString("page_url");
          Instant readAt = rs.getTimestamp("read_at").toInstant();
          StatedFigures.checked(rs.getDouble("average"), rs.getInt("scale"), rs.getInt("votes"))
              .ifPresent(figures ->
                  stated
                      .computeIfAbsent(resource, id -> new ArrayList<>())
                      .add(new DirectoryRating.Stated(host, page, figures, readAt)));
          return resource;
        })
        .list();
    return stated;
  }
}
