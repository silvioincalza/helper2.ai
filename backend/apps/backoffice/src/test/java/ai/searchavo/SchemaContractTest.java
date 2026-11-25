package ai.searchavo;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.catalog.DataOrigin;
import ai.searchavo.catalog.KnownTarget;
import ai.searchavo.catalog.ReviewQueue;
import ai.searchavo.catalog.usecase.ReportIssue;
import ai.searchavo.platform.AuditTrail;
import ai.searchavo.scout.EnrichmentOutcome;
import ai.searchavo.scout.EvidenceValidator;
import ai.searchavo.scout.RunStatus;
import ai.searchavo.search.SourceOutcome;
import ai.searchavo.shared.Audience;
import ai.searchavo.shared.DatePrecision;
import ai.searchavo.shared.Pricing;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.shared.SourceMode;
import ai.searchavo.shared.TechLevel;
import ai.searchavo.shared.ValueOrigin;
import ai.searchavo.shared.Verification;
import ai.searchavo.testing.BackendSources;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SchemaContractTest {

  static final Path SCHEMA = BackendSources.migration("V1__schema.sql");

  static JdbcClient jdbc;

  @BeforeAll
  static void migrate() {
    var postgres = TestcontainersConfiguration.POSTGRES;
    var dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbc = JdbcClient.create(dataSource);
  }

  @Test
  void everyForeignKeyDeletesAsRetentionNeeds() {
    Map<String, String> actual = new TreeMap<>();
    jdbc.sql(
            """
            SELECT k.table_name || '.' || k.column_name AS fk, u.table_name || ' ' || r.delete_rule AS rule
            FROM information_schema.referential_constraints r
            JOIN information_schema.key_column_usage k
              ON k.constraint_name = r.constraint_name AND k.constraint_schema = r.constraint_schema
            JOIN information_schema.constraint_column_usage u
              ON u.constraint_name = r.constraint_name AND u.constraint_schema = r.constraint_schema
            WHERE r.constraint_schema = 'public'
            """)
        .query((rs, i) -> actual.put(rs.getString("fk"), rs.getString("rule")))
        .list();

    assertThat(actual)
        .isEqualTo(
            new TreeMap<>(
                Map.ofEntries(
                    Map.entry("resource.published_source_id", "source RESTRICT"),
                    Map.entry("resource.updated_source_id", "source RESTRICT"),
                    Map.entry("source_record.source_id", "source RESTRICT"),
                    Map.entry("source_cursor.source_id", "source CASCADE"),
                    Map.entry("scout_run.source_id", "source CASCADE"),
                    Map.entry("enrichment_queue_control.resource_id", "resource CASCADE"),
                    Map.entry("known_target_name.target_id", "known_target CASCADE"),
                    Map.entry("list_seed_last_read.address", "list_seed CASCADE"),
                    Map.entry("review_research.review_item_id", "review_item CASCADE"),
                    Map.entry("review_research_step.research_id", "review_research CASCADE"),
                    Map.entry("resource_label.resource_id", "resource CASCADE"),
                    Map.entry("source_record.resource_id", "resource CASCADE"),
                    Map.entry("resource_embedding.resource_id", "resource CASCADE"),
                    Map.entry("resource_embedding_refused.resource_id", "resource CASCADE"),
                    Map.entry("awesome_list_entry.list_id", "resource CASCADE"),
                    Map.entry("awesome_list_entry.resource_id", "resource CASCADE"),
                    Map.entry("awesome_list.list_id", "resource CASCADE"),
                    Map.entry("directory_list_entry.resource_id", "resource CASCADE"),
                    Map.entry("directory_rating.resource_id", "resource CASCADE"),
                    Map.entry("evidence.source_record_id", "source_record CASCADE"),
                    Map.entry("enrichment_run.resource_id", "resource CASCADE"),
                    Map.entry("field_assertion.resource_id", "resource CASCADE"),
                    Map.entry("field_assertion_evidence.assertion_id", "field_assertion CASCADE"),
                    Map.entry("field_assertion_evidence.evidence_id", "evidence CASCADE"),
                    Map.entry("relationship.from_resource_id", "resource CASCADE"),
                    Map.entry("relationship_evidence.relationship_id", "relationship CASCADE"),
                    Map.entry("relationship_evidence.evidence_id", "evidence CASCADE"),
                    Map.entry("summary_claim.resource_id", "resource CASCADE"),
                    Map.entry("summary_claim_evidence.claim_id", "summary_claim CASCADE"),
                    Map.entry("summary_claim_evidence.evidence_id", "evidence CASCADE"),
                    Map.entry("issue_report.resource_id", "resource CASCADE"),
                    Map.entry("review_item.resource_id", "resource CASCADE"),
                    Map.entry("review_item.report_id", "issue_report CASCADE"),
                    Map.entry("bookmark.user_id", "app_user CASCADE"),
                    Map.entry("field_assertion.enrichment_run_id", "enrichment_run NO ACTION"),
                    Map.entry("relationship.enrichment_run_id", "enrichment_run NO ACTION"),
                    Map.entry("summary_claim.enrichment_run_id", "enrichment_run NO ACTION"),
                    Map.entry("review_item.enrichment_run_id", "enrichment_run NO ACTION"),
                    Map.entry("relationship.to_resource_id", "resource SET NULL"),
                    Map.entry("review_item.relationship_id", "relationship SET NULL"))));
  }

  @Test
  void everyForeignKeyStatesItsDeleteRule() throws IOException {
    Matcher references =
        Pattern.compile("REFERENCES \\w+ \\(\\w+\\)(?! ON DELETE)")
            .matcher(Files.readString(SCHEMA));
    List<String> implicit =
        Stream.iterate(references.find(), found -> found, found -> references.find())
            .map(found -> references.group())
            .toList();
    assertThat(implicit).isEmpty();
  }

  @Test
  void everyForeignKeyADeleteFollowsLeadsAnIndex() {
    List<String> unindexed =
        jdbc.sql(
                """
                SELECT c.conrelid::regclass || '.' || a.attname
                FROM pg_constraint c
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                WHERE c.contype = 'f' AND c.connamespace = 'public'::regnamespace
                  AND c.confrelid <> 'source'::regclass
                  AND NOT EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.conrelid AND i.indkey[0] = c.conkey[1])
                ORDER BY 1
                """)
            .query(String.class)
            .list();
    assertThat(unindexed).isEmpty();
  }

  @Test
  void everyCheckListIsTheDomainVocabularyItStores() {
    Map<String, Set<String>> expected = new TreeMap<>();
    expected.put("resource.type", names(ResourceType.values()));
    expected.put("resource.audience", names(Audience.values()));
    expected.put("resource.tech_level", names(TechLevel.values()));
    expected.put("resource.pricing", names(Pricing.values()));
    expected.put("resource.published_precision", names(DatePrecision.values()));
    expected.put("resource.data_origin", names(DataOrigin.values()));
    expected.put(
        "resource.description_origin",
        without(without(names(ValueOrigin.values()), "AI_ESTIMATED"), "SELF_DECLARED"));
    expected.put(
        "resource.enrichment_status",
        with(
            without(without(names(EnrichmentOutcome.values()), "UNCHANGED"), "FAILED"), "NOT_RUN"));
    expected.put("source.mode", names(SourceMode.values()));
    expected.put("source.last_outcome", names(SourceOutcome.values()));
    expected.put("enrichment_run.outcome", without(names(EnrichmentOutcome.values()), "UNCHANGED"));
    expected.put("field_assertion.verification", names(Verification.values()));
    expected.put(
        "field_assertion.origin",
        Set.of("AI_EXTRACTED", "AI_ESTIMATED", "SELF_DECLARED", "CURATED"));
    expected.put("relationship.origin", Set.of("AI_EXTRACTED", "SELF_DECLARED"));
    expected.put("relationship.target_origin", Set.of("CURATED"));
    expected.put("relationship.target_kind", names(KnownTarget.Kind.values()));
    expected.put("relationship.stated_target_kind", EvidenceValidator.Companion.getSTATED_KINDS());
    expected.put("known_target.kind", names(KnownTarget.Kind.values()));
    expected.put("known_target.origin", names(KnownTarget.Origin.values()));
    expected.put("relationship.verification", names(Verification.values()));
    expected.put("relationship.kind", EvidenceValidator.Companion.getRELATIONSHIP_KINDS());
    expected.put("issue_report.kind", names(ReportIssue.Kind.values()));
    expected.put("review_item.kind", names(ReviewQueue.Kind.values()));
    expected.put("review_item.status", names(ReviewQueue.Status.values()));
    expected.put("candidate_decision.status", without(names(ReviewQueue.Status.values()), "OPEN"));
    expected.put(
        "candidate_decision.kind",
        Set.of(
            "RESOURCE_CANDIDATE",
            "EXAMPLE_CANDIDATE",
            "COURSE_CANDIDATE",
            "KNOWN_TARGET_CANDIDATE"));
    expected.put("admin_audit.action", names(AuditTrail.Action.values()));
    expected.put("scout_run.status", names(RunStatus.values()));

    Map<String, Set<String>> actual = new TreeMap<>();
    jdbc.sql(
            """
            SELECT c.conrelid::regclass || '.' || a.attname AS col, pg_get_constraintdef(c.oid) AS def
            FROM pg_constraint c
            JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
            WHERE c.contype = 'c' AND c.connamespace = 'public'::regnamespace AND cardinality(c.conkey) = 1
            """)
        .query((rs, i) -> {
          String col = rs.getString("col");
          if (expected.containsKey(col)) {
            actual.put(col, literals(rs.getString("def")));
          }
          return col;
        })
        .list();
    assertThat(actual).isEqualTo(expected);
  }

  // UC-013 BR-004: the table and the value object refuse a text at the same length, so a caller
  // that passes the value object never meets a refusal of the database.
  @Test
  void theAuditTableHoldsWhatAnAuditChangeHolds() {
    List<String> held =
        jdbc.sql(
                """
                SELECT a.attname AS col, pg_get_constraintdef(c.oid) AS def
                FROM pg_constraint c
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                WHERE c.contype = 'c' AND c.conrelid = 'admin_audit'::regclass AND cardinality(c.conkey) = 1
                  AND a.attname IN ('before_value', 'after_value', 'reason')
                """)
            .query((rs, i) -> {
              Matcher m = Pattern.compile("<= (\\d+)").matcher(rs.getString("def"));
              assertThat(m.find()).as(rs.getString("def")).isTrue();
              return rs.getString("col") + " " + m.group(1);
            })
            .list();
    assertThat(held)
        .containsExactlyInAnyOrder(
            "before_value " + AuditTrail.Change.MAX_LENGTH,
            "after_value " + AuditTrail.Change.MAX_LENGTH,
            "reason " + AuditTrail.Change.MAX_LENGTH);
  }

  private static Set<String> literals(String checkDefinition) {
    Matcher m = Pattern.compile("'([^']+)'::text").matcher(checkDefinition);
    Set<String> values = new TreeSet<>();
    while (m.find()) {
      values.add(m.group(1));
    }
    return values;
  }

  private static Set<String> names(Enum<?>[] values) {
    return Arrays.stream(values).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
  }

  private static Set<String> without(Set<String> values, String value) {
    Set<String> copy = new TreeSet<>(values);
    copy.remove(value);
    return copy;
  }

  private static Set<String> with(Set<String> values, String value) {
    Set<String> copy = new TreeSet<>(values);
    copy.add(value);
    return copy;
  }
}
