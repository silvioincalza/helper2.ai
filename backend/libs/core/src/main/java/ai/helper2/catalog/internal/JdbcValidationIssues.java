package ai.helper2.catalog.internal;

import ai.helper2.catalog.enrichment.ValidationIssues;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcValidationIssues implements ValidationIssues {

  private record Row(Count count, int total) {}

  private static final String LINES =
      """
      WITH line AS (
             SELECT r.id AS run, e.line
             FROM enrichment_run r, jsonb_array_elements_text(r.validation_errors) AS e(line)
             WHERE r.started_at >= :since),
           issue AS (
             SELECT run,
                    CASE
                      WHEN line LIKE 'field %%' THEN split_part(split_part(line, ':', 1), ' ', 1)
                                                   || ' ' || split_part(split_part(line, ':', 1), ' ', 2)
                      WHEN line LIKE 'relationship %%' THEN 'relationship ' || split_part(line, ' ', 2)
                      WHEN line LIKE 'summary claim%%' THEN 'summary claim'
                      ELSE 'other'
                    END AS subject,
                    btrim(regexp_replace(regexp_replace(regexp_replace(
                      CASE WHEN position(': ' IN line) > 0 THEN split_part(line, ': ', 2) ELSE line END,
                      '\\([^)]*\\)', '', 'g'), '"[^"]*"', '', 'g'), '[0-9]+', 'N', 'g')) AS rule
             FROM line)
      %s
      """;

  private final JdbcClient jdbc;

  JdbcValidationIssues(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Summary since(Instant since, int top) {
    List<Row> rows =
        jdbc.sql(
                LINES.formatted(
                    """
                    SELECT subject, rule, count(DISTINCT run) AS runs,
                           (SELECT count(DISTINCT run) FROM issue) AS total
                    FROM issue
                    GROUP BY subject, rule ORDER BY runs DESC, subject, rule LIMIT :top
                    """))
            .param("since", Timestamp.from(since))
            .param("top", top)
            .query((rs, n) ->
                new Row(
                    new Count(rs.getString("subject"), rs.getString("rule"), rs.getInt("runs")),
                    rs.getInt("total")))
            .list();
    return new Summary(
        rows.isEmpty() ? 0 : rows.getFirst().total(), rows.stream().map(Row::count).toList());
  }
}
