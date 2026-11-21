package ai.helper2.catalog.internal;

import ai.helper2.catalog.GoneLists;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

class JdbcGoneLists implements GoneLists {

  private final JdbcClient jdbc;

  JdbcGoneLists(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  @Override
  public Forgotten forget(Collection<UUID> notFoundRecords, int notFoundVisits) {
    UUID[] gone = goneRepositories(notFoundRecords, notFoundVisits);
    if (gone.length == 0) {
      return new Forgotten(0, List.of());
    }
    Set<UUID> lists = new HashSet<>();
    Set<UUID> named = new LinkedHashSet<>();
    jdbc.sql(
            """
            DELETE FROM awesome_list_entry WHERE list_id = ANY(:gone)
            RETURNING list_id, resource_id
            """)
        .param("gone", gone)
        .query(rs -> {
          lists.add(rs.getObject("list_id", UUID.class));
          named.add(rs.getObject("resource_id", UUID.class));
        });
    lists.addAll(
        jdbc.sql("DELETE FROM awesome_list WHERE list_id = ANY(:gone) RETURNING list_id")
            .param("gone", gone)
            .query(UUID.class)
            .list());
    return new Forgotten(lists.size(), List.copyOf(named));
  }

  // UC-030 BR-010: a list is gone when the record that is its repository is marked gone
  // (UC-018 BR-009) and GitHub did not find it on the set number of visits in a row. A record
  // that only links the repository never counts.
  private UUID[] goneRepositories(Collection<UUID> notFoundRecords, int notFoundVisits) {
    return jdbc.sql(
            """
            SELECT DISTINCT resource_id
            FROM source_record
            WHERE id = ANY(:records)
              AND repository_url IS NULL
              AND coalesce(CAST(extra -> 'vitality' ->> 'gone' AS boolean), false)
              AND coalesce(CAST(extra -> 'vitality' ->> 'notFoundVisits' AS integer), 0) >= :visits
            """)
        .param("records", notFoundRecords.toArray(UUID[]::new))
        .param("visits", notFoundVisits)
        .query(UUID.class)
        .list()
        .toArray(UUID[]::new);
  }
}
