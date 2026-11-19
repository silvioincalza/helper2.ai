package ai.helper2.catalog.internal;

import ai.helper2.catalog.AwesomeListEntries;
import ai.helper2.catalog.EnrichmentFacts;
import ai.helper2.catalog.ResourceDetail;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.shared.Verification;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcResourceDetailReads implements EnrichmentFacts, AwesomeListEntries {

  private final JdbcClient jdbc;

  JdbcResourceDetailReads(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<ResourceDetail.Assertion> assertions(UUID resourceId) {
    List<Owned<ResourceDetail.Assertion>> rows =
        jdbc.sql(
                """
                SELECT a.id, a.field, a.value, a.origin, a.verification, run.model
                FROM field_assertion a LEFT JOIN enrichment_run run ON run.id = a.enrichment_run_id
                WHERE a.resource_id = :id ORDER BY a.field
                """)
            .param("id", resourceId)
            .query((rs, n) -> {
              String field = rs.getString("field");
              String value = rs.getString("value");
              ValueOrigin origin = ValueOrigin.valueOf(rs.getString("origin"));
              Verification verification = Verification.valueOf(rs.getString("verification"));
              String model = rs.getString("model");
              return new Owned<>(
                  rs.getObject("id", UUID.class),
                  evidence ->
                      new ResourceDetail.Assertion(
                          field, value, origin, verification, null, evidence, model));
            })
            .list();
    return withEvidence(rows, "field_assertion_evidence", "assertion_id");
  }

  @Override
  public List<ResourceDetail.Relationship> relationships(UUID resourceId) {
    List<Owned<ResourceDetail.Relationship>> rows =
        jdbc.sql(
                """
                SELECT rel.id, rel.kind, rel.to_resource_id, rel.to_title, rel.verification, rel.origin, run.model
                FROM relationship rel LEFT JOIN enrichment_run run ON run.id = rel.enrichment_run_id
                WHERE rel.from_resource_id = :id AND rel.verification <> 'REJECTED'
                ORDER BY rel.kind, rel.to_title
                """)
            .param("id", resourceId)
            .query((rs, n) -> {
              String kind = rs.getString("kind");
              UUID target = rs.getObject("to_resource_id", UUID.class);
              String title = rs.getString("to_title");
              Verification verification = Verification.valueOf(rs.getString("verification"));
              ValueOrigin origin = ValueOrigin.valueOf(rs.getString("origin"));
              String model = rs.getString("model");
              return new Owned<>(
                  rs.getObject("id", UUID.class),
                  evidence ->
                      new ResourceDetail.Relationship(
                          kind, target, title, verification, evidence, origin, model));
            })
            .list();
    return withEvidence(rows, "relationship_evidence", "relationship_id");
  }

  @Override
  public ResourceDetail.@Nullable Summary summary(UUID resourceId) {
    return jdbc.sql(
            """
            SELECT r.id, r.model, r.prompt_version, r.finished_at FROM enrichment_run r
            WHERE r.resource_id = :id AND r.outcome IN ('VALIDATED','PARTIAL')
              AND EXISTS (SELECT 1 FROM summary_claim c WHERE c.enrichment_run_id = r.id)
            ORDER BY r.started_at DESC LIMIT 1
            """)
        .param("id", resourceId)
        .query((rs, n) ->
            new RunRow(
                rs.getObject("id", UUID.class),
                rs.getString("model"),
                rs.getString("prompt_version"),
                rs.getTimestamp("finished_at").toInstant()))
        .optional()
        .map(run -> {
          List<Owned<ResourceDetail.SummaryClaim>> rows =
              jdbc.sql(
                      "SELECT id, text, self_declared FROM summary_claim WHERE enrichment_run_id = :run ORDER BY position")
                  .param("run", run.id())
                  .query((c, m) -> {
                    String text = c.getString("text");
                    boolean selfDeclared = c.getBoolean("self_declared");
                    return new Owned<>(
                        c.getObject("id", UUID.class),
                        evidence -> new ResourceDetail.SummaryClaim(text, evidence, selfDeclared));
                  })
                  .list();
          return new ResourceDetail.Summary(
              withEvidence(rows, "summary_claim_evidence", "claim_id"),
              run.model(),
              run.promptVersion(),
              run.finishedAt());
        })
        .orElse(null);
  }

  @Override
  public ResourceDetail.FoundIn naming(UUID resourceId, int limit) {
    List<ListRow> rows =
        jdbc.sql(
                """
                SELECT e.list_id, l.title, e.heading_path, count(*) OVER () AS total
                FROM awesome_list_entry e JOIN resource l ON l.id = e.list_id
                WHERE e.resource_id = :id
                ORDER BY l.popularity DESC, l.title, e.list_id
                LIMIT :limit
                """)
            .param("id", resourceId)
            .param("limit", limit)
            .query((rs, n) ->
                new ListRow(
                    new ResourceDetail.ListEntry(
                        rs.getObject("list_id", UUID.class),
                        rs.getString("title"),
                        rs.getString("heading_path")),
                    rs.getInt("total")))
            .list();
    if (rows.isEmpty()) {
      return ResourceDetail.FoundIn.NO_LIST;
    }
    return new ResourceDetail.FoundIn(
        rows.stream().map(ListRow::entry).toList(), rows.getFirst().total() - rows.size());
  }

  private record ListRow(ResourceDetail.ListEntry entry, int total) {}

  private record RunRow(UUID id, String model, String promptVersion, Instant finishedAt) {}

  private record Owned<T>(UUID id, Function<List<ResourceDetail.Evidence>, T> build) {}

  private <T> List<T> withEvidence(List<Owned<T>> rows, String joinTable, String fk) {
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<UUID, List<ResourceDetail.Evidence>> evidence =
        evidenceOf(joinTable, fk, rows.stream().map(Owned::id).toList());
    return rows.stream()
        .map(r -> r.build().apply(evidence.getOrDefault(r.id(), List.of())))
        .toList();
  }

  private Map<UUID, List<ResourceDetail.Evidence>> evidenceOf(
      String joinTable, String fk, List<UUID> owners) {
    Map<UUID, List<ResourceDetail.Evidence>> byOwner = new HashMap<>();
    jdbc.sql(
            """
            SELECT j.%2$s AS owner, e.id, e.source_url, e.retrieved_at, e.excerpt FROM evidence e
            JOIN %1$s j ON j.evidence_id = e.id WHERE j.%2$s = ANY(:owners) ORDER BY j.%2$s, e.retrieved_at
            """
                .formatted(joinTable, fk))
        .param("owners", owners.toArray(UUID[]::new))
        .query(rs -> {
          byOwner
              .computeIfAbsent(rs.getObject("owner", UUID.class), k -> new ArrayList<>())
              .add(
                  new ResourceDetail.Evidence(
                      rs.getObject("id", UUID.class),
                      URI.create(rs.getString("source_url")),
                      rs.getTimestamp("retrieved_at").toInstant(),
                      rs.getString("excerpt")));
        });
    return byOwner;
  }
}
