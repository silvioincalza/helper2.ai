package ai.helper2.scout.internal;

import ai.helper2.scout.ListKind;
import ai.helper2.scout.ListOrigin;
import ai.helper2.scout.ListReading;
import ai.helper2.scout.ListSeed;
import ai.helper2.scout.ListSeedStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

@Component
class JdbcListSeedStore implements ListSeedStore {

  private static final String COLUMNS =
      "kind, address, added_by, added_at, reason, read_tried_at, read_failures, origin";

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  JdbcListSeedStore(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public List<ListSeed> all() {
    return jdbc.sql("SELECT " + COLUMNS + " FROM list_seed ORDER BY added_at, address")
        .query((rs, i) -> seed(rs))
        .list();
  }

  @Override
  public boolean add(ListSeed seed) {
    return jdbc.sql(
                """
                INSERT INTO list_seed (kind, address, added_by, added_at, reason, origin)
                VALUES (:kind, :address, :by, :at, :reason, :origin)
                ON CONFLICT (address) DO NOTHING
                """)
            .param("kind", seed.getKind().name())
            .param("address", seed.getAddress())
            .param("by", seed.getAddedBy())
            .param("at", Timestamp.from(seed.getAddedAt()))
            .param("reason", seed.getReason())
            .param("origin", seed.getOrigin().name())
            .update()
        == 1;
  }

  @Override
  public @Nullable ListSeed remove(String address) {
    return jdbc.sql("DELETE FROM list_seed WHERE address = :address RETURNING " + COLUMNS)
        .param("address", address)
        .query((rs, i) -> seed(rs))
        .optional()
        .orElse(null);
  }

  @Override
  public void readStarted(String address, Instant at) {
    jdbc.sql(
            """
            UPDATE list_seed SET read_tried_at = :at, read_failures = read_failures + 1
            WHERE address = :address AND kind = 'WEB_PAGE'
            """)
        .param("at", Timestamp.from(at))
        .param("address", address)
        .update();
  }

  @Transactional
  @Override
  public void readSucceeded(String address, ListReading reading) {
    jdbc.sql("UPDATE list_seed SET read_failures = 0 WHERE address = :address")
        .param("address", address)
        .update();
    jdbc.sql(
            """
            INSERT INTO list_seed_last_read
                (address, read_at, headings, entries, links, text_lines, linked_items)
            SELECT address, :at, CAST(:headings AS jsonb), CAST(:entries AS jsonb),
                   CAST(:links AS jsonb), :textLines, :linkedItems
            FROM list_seed WHERE address = :address AND kind = 'WEB_PAGE'
            ON CONFLICT (address) DO UPDATE SET
                read_at = EXCLUDED.read_at, headings = EXCLUDED.headings,
                entries = EXCLUDED.entries, links = EXCLUDED.links,
                text_lines = EXCLUDED.text_lines, linked_items = EXCLUDED.linked_items,
                followed = '[]'
            """)
        .param("address", address)
        .param("at", Timestamp.from(reading.getReadAt()))
        .param("headings", headings(reading))
        .param("entries", entries(reading))
        .param("links", links(reading))
        .param("textLines", reading.getTextLines())
        .param("linkedItems", reading.getLinkedItems())
        .update();
  }

  // UC-030 BR-013: what following gave belongs to one read; a new read starts it again.
  @Override
  public void followed(String address, List<ListReading.Followed> followed) {
    ArrayNode rows = json.createArrayNode();
    for (ListReading.Followed f : followed) {
      rows.addObject()
          .put("link", f.getLink())
          .put("item", f.getItem())
          .put("steps", f.getSteps())
          .put("decidedBy", f.getDecidedBy());
    }
    jdbc.sql(
            "UPDATE list_seed_last_read SET followed = CAST(:followed AS jsonb) WHERE address = :address")
        .param("followed", json.writeValueAsString(rows))
        .param("address", address)
        .update();
  }

  @Override
  public Map<String, ListReading> lastReads() {
    Map<String, ListReading> readings = new LinkedHashMap<>();
    jdbc.sql(
            """
            SELECT address, read_at, headings, entries, links, text_lines, linked_items, followed
            FROM list_seed_last_read ORDER BY address
            """)
        .query(rs -> {
          readings.put(rs.getString("address"), reading(rs));
        });
    return readings;
  }

  private String headings(ListReading reading) {
    ArrayNode rows = json.createArrayNode();
    reading.getHeadings().forEach(rows::add);
    return json.writeValueAsString(rows);
  }

  private String entries(ListReading reading) {
    ArrayNode rows = json.createArrayNode();
    for (ListReading.Entry e : reading.getEntries()) {
      rows.addObject()
          .put("repository", e.getRepository())
          .put("section", e.getSection())
          .put("headingPath", e.getHeadingPath())
          .put("text", e.getText())
          .put("inListOfLists", e.getInListOfLists());
    }
    return json.writeValueAsString(rows);
  }

  private String links(ListReading reading) {
    ArrayNode rows = json.createArrayNode();
    for (ListReading.Link l : reading.getLinks()) {
      rows.addObject()
          .put("url", l.getUrl())
          .put("words", l.getWords())
          .put("section", l.getSection())
          .put("headingPath", l.getHeadingPath())
          .put("entryText", l.getEntryText());
    }
    return json.writeValueAsString(rows);
  }

  private ListReading reading(ResultSet rs) throws SQLException {
    List<String> headings = new ArrayList<>();
    json.readTree(rs.getString("headings")).forEach(h -> headings.add(h.asString("")));
    List<ListReading.Entry> entries = new ArrayList<>();
    for (JsonNode e : json.readTree(rs.getString("entries"))) {
      entries.add(
          new ListReading.Entry(
              e.path("repository").asString(""),
              e.path("section").asString(""),
              e.path("headingPath").asString(""),
              e.path("text").asString(""),
              e.path("inListOfLists").asBoolean(false)));
    }
    List<ListReading.Link> links = new ArrayList<>();
    for (JsonNode l : json.readTree(rs.getString("links"))) {
      links.add(
          new ListReading.Link(
              l.path("url").asString(""),
              l.path("words").asString(""),
              l.path("section").asString(""),
              l.path("headingPath").asString(""),
              l.path("entryText").asString("")));
    }
    List<ListReading.Followed> followed = new ArrayList<>();
    for (JsonNode f : json.readTree(rs.getString("followed"))) {
      followed.add(
          new ListReading.Followed(
              f.path("link").asString(""),
              f.path("item").isString() ? f.path("item").asString() : null,
              f.path("steps").asInt(0),
              f.path("decidedBy").isString() ? f.path("decidedBy").asString() : null));
    }
    return new ListReading(
        rs.getTimestamp("read_at").toInstant(),
        headings,
        entries,
        links,
        rs.getInt("text_lines"),
        rs.getInt("linked_items"),
        followed);
  }

  private static ListSeed seed(ResultSet rs) throws SQLException {
    Timestamp tried = rs.getTimestamp("read_tried_at");
    return new ListSeed(
        ListKind.valueOf(rs.getString("kind")),
        rs.getString("address"),
        rs.getString("added_by"),
        rs.getTimestamp("added_at").toInstant(),
        rs.getString("reason"),
        tried == null ? null : tried.toInstant(),
        rs.getInt("read_failures"),
        ListOrigin.valueOf(rs.getString("origin")));
  }
}
