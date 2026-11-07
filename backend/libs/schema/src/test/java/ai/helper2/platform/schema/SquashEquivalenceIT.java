package ai.helper2.platform.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The squash gate of the first release (ADR 0004 §5): the old chain V1..V23 and the single V1 build
 * the same database. Each runs through Flyway, with today's R__ seeds, in a fresh container of the
 * test image; then the two are compared: pg_dump --schema-only per object, the privileges on the
 * database, the schema, every table and sequence, the default privileges, the roles, and the rows
 * of every table. Only the differences listed in {@link #INTENDED} may remain.
 *
 * <p>The old chain is read from git (h2.squash.old-ref, the tip of next/3.0 before the squash), so
 * this runs on a full checkout only: {@code ./gradlew :libs:schema:squashCheck}. The report goes to
 * the log and to libs/schema/build/squash-equivalence.txt.
 */
@Tag("squash")
class SquashEquivalenceIT {

  /** The image of TestcontainersConfiguration (libs/platform test fixtures) and compose.yaml. */
  static final String IMAGE = "pgvector/pgvector:0.8.6-pg18-trixie";

  static final String MIGRATIONS = "libs/schema/src/main/resources/db/migration";

  static final String OLD_REF =
      System.getProperty("h2.squash.old-ref", "055afc1b33846c978b2d73defe8708047168a774");

  /** Every difference the squash means to make, each with its reason. */
  static final Map<String, String> INTENDED =
      new TreeMap<>(
          Map.ofEntries(
              // V18 renamed tavily_query_result to live_web_query_result, and its index, but not
              // its primary key, its check or its NOT NULL constraints. V1 creates the table under
              // its final name, so every constraint has that name too. No code names them.
              Map.entry(
                  "schema: only old: CONSTRAINT live_web_query_result tavily_query_result_pkey",
                  "the old chain kept the table's first name on its primary key (V18)"),
              Map.entry(
                  "schema: only new: CONSTRAINT live_web_query_result live_web_query_result_pkey",
                  "the primary key under the table's final name"),
              Map.entry(
                  "schema: changed: TABLE live_web_query_result",
                  "the check and NOT NULL constraints under the table's final name"),
              // The plpgsql functions set their search_path: a load with pg_restore (empty
              // search_path) stopped at the first source_record before.
              Map.entry(
                  "schema: changed: FUNCTION h2_append_only()",
                  "SET search_path = public, pg_catalog"),
              Map.entry(
                  "schema: changed: FUNCTION h2_refresh_popularity()",
                  "SET search_path = public, pg_catalog"),
              Map.entry(
                  "function settings: only old: h2_append_only(): -", "no search_path before"),
              Map.entry(
                  "function settings: only new: h2_append_only(): search_path=public, pg_catalog",
                  "the function's own search_path"),
              Map.entry(
                  "function settings: only old: h2_refresh_popularity(): -",
                  "no search_path before"),
              Map.entry(
                  "function settings: only new: h2_refresh_popularity(): search_path=public,"
                      + " pg_catalog",
                  "the function's own search_path")));

  /** Columns whose values each run makes anew: random ids and now(). Compared by count only. */
  static final Set<String> VOLATILE_TYPES =
      Set.of("uuid", "timestamp with time zone", "timestamp without time zone");

  @Test
  void theSingleV1BuildsTheDatabaseTheOldChainBuilt() throws Exception {
    Path oldChain = oldChain();
    try (var oldDb = container();
        var newDb = container()) {
      oldDb.start();
      newDb.start();
      migrate(oldDb, "filesystem:" + oldChain);
      migrate(newDb, "filesystem:" + MIGRATIONS);

      List<String> differences = new ArrayList<>();
      differences.addAll(compareDumps(dump(oldDb), dump(newDb)));
      for (var query : PRIVILEGE_QUERIES.entrySet()) {
        List<String> before = rows(oldDb, query.getValue());
        assertThat(before).as(query.getKey() + " in the old chain").isNotEmpty();
        differences.addAll(compareSets(query.getKey(), before, rows(newDb, query.getValue())));
      }
      List<String> notes = new ArrayList<>();
      differences.addAll(compareRows(jdbc(oldDb), jdbc(newDb), notes));

      String report = report(differences, notes);
      System.out.println(report);
      Files.writeString(Path.of("libs/schema/build/squash-equivalence.txt"), report);

      Set<String> found = differences.stream().map(SquashEquivalenceIT::key).collect(toTreeSet());
      assertThat(found).as(report).isEqualTo(INTENDED.keySet());
    }
  }

  // ---- the two chains

  /** V1..V23 at OLD_REF, read from git, and the worktree's R__ seeds next to them. */
  private static Path oldChain() throws IOException, InterruptedException {
    Path dir = Files.createTempDirectory("squash-old-chain");
    String repoPath = "backend/" + MIGRATIONS;
    for (String name :
        git("ls-tree", "--full-tree", "--name-only", OLD_REF, repoPath + "/").split("\n")) {
      String file = name.substring(name.lastIndexOf('/') + 1);
      if (file.startsWith("V")) {
        Files.writeString(dir.resolve(file), git("show", OLD_REF + ":" + name));
      }
    }
    try (Stream<Path> seeds = Files.list(Path.of(MIGRATIONS))) {
      for (Path seed : seeds.filter(p -> p.getFileName().toString().startsWith("R__")).toList()) {
        Files.copy(seed, dir.resolve(seed.getFileName()));
      }
    }
    try (Stream<Path> files = Files.list(dir)) {
      assertThat(files.filter(p -> p.getFileName().toString().startsWith("V")).count())
          .as("versioned migrations at " + OLD_REF)
          .isEqualTo(23);
    }
    return dir;
  }

  private static String git(String... args) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0) {
      throw new IllegalStateException(String.join(" ", command) + ": " + out);
    }
    return out;
  }

  private static PostgreSQLContainer container() {
    return new PostgreSQLContainer(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
        .withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF8 --locale=en_US.utf8");
  }

  private static void migrate(PostgreSQLContainer db, String location) {
    Flyway.configure().dataSource(dataSource(db)).locations(location).load().migrate();
  }

  private static DriverManagerDataSource dataSource(PostgreSQLContainer db) {
    return new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
  }

  private static JdbcClient jdbc(PostgreSQLContainer db) {
    return JdbcClient.create(dataSource(db));
  }

  // ---- pg_dump, object by object

  static final Pattern HEADER =
      Pattern.compile("^-- Name: (.*); Type: (.*); Schema: (.*); Owner: .*$", Pattern.MULTILINE);

  private static String dump(PostgreSQLContainer db) throws IOException, InterruptedException {
    var result =
        db.execInContainer(
            "pg_dump",
            "--schema-only",
            "--no-owner",
            "-U",
            db.getUsername(),
            "-d",
            db.getDatabaseName());
    assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    return result.getStdout();
  }

  /** Each object of the dump under its header: "TABLE resource" -> its statements, no comments. */
  static Map<String, String> objects(String dump) {
    Map<String, String> objects = new TreeMap<>();
    Matcher header = HEADER.matcher(dump);
    List<int[]> starts = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    while (header.find()) {
      starts.add(new int[] {header.start(), header.end()});
      keys.add(header.group(2) + " " + header.group(1));
    }
    for (int i = 0; i < keys.size(); i++) {
      int end = i + 1 < keys.size() ? starts.get(i + 1)[0] : dump.length();
      String body =
          dump.substring(starts.get(i)[1], end)
              .lines()
              .filter(line -> !line.isBlank() && !line.startsWith("--"))
              .filter(line -> !line.startsWith("\\restrict") && !line.startsWith("\\unrestrict"))
              .collect(Collectors.joining("\n"));
      objects.merge(keys.get(i), body, (a, b) -> a + "\n" + b);
    }
    return objects;
  }

  private static List<String> compareDumps(String oldDump, String newDump) {
    Map<String, String> before = objects(oldDump);
    Map<String, String> after = objects(newDump);
    assertThat(before.keySet()).as("the dump has the grants").anyMatch(k -> k.startsWith("ACL "));
    List<String> differences = new ArrayList<>();
    for (String object : union(before.keySet(), after.keySet())) {
      String a = before.get(object);
      String b = after.get(object);
      if (a == null) {
        differences.add("schema: only new: " + object + "\n" + indent(b));
      } else if (b == null) {
        differences.add("schema: only old: " + object + "\n" + indent(a));
      } else if (!a.equals(b)) {
        differences.add("schema: changed: " + object + "\n" + lineDiff(a, b));
      }
    }
    return differences;
  }

  // ---- privileges and roles

  static final Map<String, String> PRIVILEGE_QUERIES = new LinkedHashMap<>();

  static {
    PRIVILEGE_QUERIES.put(
        "database privileges",
        """
        SELECT coalesce(g.rolname, 'PUBLIC') || ' ' || a.privilege_type || ' by ' || r.rolname
        FROM pg_database d, aclexplode(d.datacl) a
        LEFT JOIN pg_roles g ON g.oid = a.grantee JOIN pg_roles r ON r.oid = a.grantor
        WHERE d.datname = current_database()
        """);
    PRIVILEGE_QUERIES.put(
        "schema privileges",
        """
        SELECT coalesce(g.rolname, 'PUBLIC') || ' ' || a.privilege_type
        FROM pg_namespace n, aclexplode(n.nspacl) a LEFT JOIN pg_roles g ON g.oid = a.grantee
        WHERE n.nspname = 'public'
        """);
    PRIVILEGE_QUERIES.put(
        "table and sequence privileges",
        """
        SELECT c.relkind::text || ' ' || c.relname || ': ' || coalesce(g.rolname, 'PUBLIC') || ' ' || a.privilege_type
        FROM pg_class c, aclexplode(c.relacl) a LEFT JOIN pg_roles g ON g.oid = a.grantee
        WHERE c.relnamespace = 'public'::regnamespace
        """);
    PRIVILEGE_QUERIES.put(
        "default privileges",
        """
        SELECT d.defaclrole::regrole::text || ' ' || d.defaclobjtype::text || ' ' || coalesce(n.nspname, '*') || ': '
               || coalesce(g.rolname, 'PUBLIC') || ' ' || a.privilege_type
        FROM pg_default_acl d LEFT JOIN pg_namespace n ON n.oid = d.defaclnamespace,
             aclexplode(d.defaclacl) a LEFT JOIN pg_roles g ON g.oid = a.grantee
        """);
    PRIVILEGE_QUERIES.put(
        "roles",
        """
        SELECT rolname || ': super=' || rolsuper || ' inherit=' || rolinherit || ' createrole=' || rolcreaterole
               || ' createdb=' || rolcreatedb || ' login=' || rolcanlogin || ' replication=' || rolreplication
               || ' bypassrls=' || rolbypassrls || ' connlimit=' || rolconnlimit
        FROM pg_roles WHERE rolname NOT LIKE 'pg\\_%'
        """);
    PRIVILEGE_QUERIES.put(
        "function settings",
        """
        SELECT p.oid::regprocedure::text || ': ' || coalesce(array_to_string(p.proconfig, ', '), '-')
        FROM pg_proc p WHERE p.pronamespace = 'public'::regnamespace
        """);
  }

  private static List<String> rows(PostgreSQLContainer db, String sql) {
    return jdbc(db).sql(sql).query(String.class).list();
  }

  private static List<String> compareSets(String what, List<String> before, List<String> after) {
    List<String> differences = new ArrayList<>();
    for (String row : union(before, after)) {
      boolean inOld = before.contains(row);
      boolean inNew = after.contains(row);
      if (inOld != inNew) {
        differences.add(what + ": only " + (inOld ? "old" : "new") + ": " + row);
      }
    }
    return differences;
  }

  // ---- rows

  private static List<String> compareRows(JdbcClient before, JdbcClient after, List<String> notes) {
    List<String> differences = new ArrayList<>();
    List<String> tables =
        before
            .sql(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
                    + " AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'"
                    + " ORDER BY 1")
            .query(String.class)
            .list();
    for (String table : tables) {
      Map<String, String> types = new LinkedHashMap<>();
      before
          .sql(
              "SELECT column_name, data_type FROM information_schema.columns"
                  + " WHERE table_schema = 'public' AND table_name = :t ORDER BY ordinal_position")
          .param("t", table)
          .query((rs, i) -> types.put(rs.getString(1), rs.getString(2)))
          .list();
      List<Map<String, String>> a = tableRows(before, table, types.keySet());
      List<Map<String, String>> b = tableRows(after, table, types.keySet());
      if (a.size() != b.size()) {
        differences.add("rows: " + table + ": " + a.size() + " old, " + b.size() + " new");
        continue;
      }
      List<String> compared = new ArrayList<>();
      List<String> byCount = new ArrayList<>();
      for (String column : types.keySet()) {
        boolean same = values(a, column).equals(values(b, column));
        if (same || !VOLATILE_TYPES.contains(types.get(column))) {
          compared.add(column);
        } else {
          byCount.add(column);
        }
      }
      if (!a.isEmpty()) {
        notes.add(
            table
                + ": "
                + a.size()
                + " rows compared"
                + (byCount.isEmpty() ? "" : "; by count only (new in each run): " + byCount));
      }
      List<String> rowsA = project(a, compared);
      List<String> rowsB = project(b, compared);
      if (!rowsA.equals(rowsB)) {
        List<String> onlyOld = new ArrayList<>(rowsA);
        onlyOld.removeAll(rowsB);
        List<String> onlyNew = new ArrayList<>(rowsB);
        onlyNew.removeAll(rowsA);
        differences.add(
            "rows: "
                + table
                + " differ\n    old: "
                + String.join("\n    old: ", onlyOld.stream().limit(5).toList())
                + "\n    new: "
                + String.join("\n    new: ", onlyNew.stream().limit(5).toList()));
      }
    }
    return differences;
  }

  private static List<Map<String, String>> tableRows(
      JdbcClient jdbc, String table, Collection<String> columns) {
    String select =
        columns.stream().map(c -> "\"" + c + "\"::text").collect(Collectors.joining(", "));
    return jdbc.sql("SELECT " + select + " FROM \"" + table + "\"")
        .query((rs, i) -> {
          Map<String, String> row = new LinkedHashMap<>();
          int n = 1;
          for (String column : columns) {
            row.put(column, rs.getString(n++));
          }
          return row;
        })
        .list();
  }

  private static List<String> values(List<Map<String, String>> rows, String column) {
    return rows.stream().map(r -> String.valueOf(r.get(column))).sorted().toList();
  }

  private static List<String> project(List<Map<String, String>> rows, List<String> columns) {
    return rows.stream()
        .map(r -> columns.stream().map(c -> c + "=" + r.get(c)).collect(Collectors.joining(" | ")))
        .sorted()
        .toList();
  }

  // ---- the report

  /** The first line of a difference: what it is about, without the detail below it. */
  private static String key(String difference) {
    return difference.lines().findFirst().orElse(difference);
  }

  private static String report(List<String> differences, List<String> notes) {
    StringBuilder out = new StringBuilder();
    out.append("Squash gate: old chain at ").append(OLD_REF).append(" vs the single V1\n");
    out.append("Rows (every other table is empty in both):\n");
    notes.forEach(note -> out.append("    ").append(note).append('\n'));
    out.append(differences.size()).append(" difference(s)\n\n");
    for (String difference : differences) {
      String reason = INTENDED.get(key(difference));
      out.append(reason == null ? "NOT LISTED  " : "intended    ").append(difference).append('\n');
      if (reason != null) {
        out.append("    why: ").append(reason).append('\n');
      }
    }
    for (String listed : INTENDED.keySet()) {
      if (differences.stream().map(SquashEquivalenceIT::key).noneMatch(listed::equals)) {
        out.append("LISTED, NOT FOUND  ").append(listed).append('\n');
      }
    }
    return out.toString();
  }

  private static String lineDiff(String a, String b) {
    List<String> before = a.lines().toList();
    List<String> after = b.lines().toList();
    StringBuilder out = new StringBuilder();
    before.stream()
        .filter(l -> !after.contains(l))
        .forEach(l -> out.append("    - ").append(l).append('\n'));
    after.stream()
        .filter(l -> !before.contains(l))
        .forEach(l -> out.append("    + ").append(l).append('\n'));
    if (out.isEmpty()) {
      out.append("    (same lines, another order)\n");
    }
    return out.toString().stripTrailing();
  }

  private static String indent(String text) {
    return text.lines().map(l -> "    " + l).collect(Collectors.joining("\n"));
  }

  private static TreeSet<String> union(Collection<String> a, Collection<String> b) {
    TreeSet<String> all = new TreeSet<>(a);
    all.addAll(b);
    return all;
  }

  private static java.util.stream.Collector<String, ?, TreeSet<String>> toTreeSet() {
    return Collectors.toCollection(TreeSet::new);
  }
}
