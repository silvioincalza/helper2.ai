package ai.helper2.platform.settings;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class JdbcPublicAppSettings implements PublicAppSettings {
  static final long LOCK = 0x4832_5055_4253_4554L;
  static final int MAX_SETTINGS = 500;

  private static final Logger log = LoggerFactory.getLogger(JdbcPublicAppSettings.class);

  private final JdbcClient jdbc;

  JdbcPublicAppSettings(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public void publish(String build, Instant at, List<SettingsGroup> groups) {
    jdbc.sql("SELECT pg_advisory_xact_lock(:lock)").param("lock", LOCK).query().singleRow();
    jdbc.sql("DELETE FROM public_app_setting").update();
    for (SettingsGroup group : groups) {
      insert(build, at, group);
    }
  }

  private void insert(String build, Instant at, SettingsGroup group) {
    int position = 0;
    for (Setting setting : group.settings()) {
      jdbc.sql(
              """
              INSERT INTO public_app_setting
                (key, group_id, group_title, group_order, position, label, kind, value, unit, variable,
                 build, published_at)
              VALUES
                (:key, :groupId, :groupTitle, :groupOrder, :position, :label, :kind, :value, :unit,
                 :variable, :build, :at)
              """)
          .param("key", setting.key())
          .param("groupId", group.id())
          .param("groupTitle", group.title())
          .param("groupOrder", group.order())
          .param("position", position++)
          .param("label", setting.label())
          .param("kind", setting.kind().name())
          .param("value", setting.value())
          .param("unit", setting.unit())
          .param("variable", setting.variable())
          .param("build", build)
          .param("at", Timestamp.from(at))
          .update();
    }
  }

  @Override
  public Optional<Publication> newest() {
    List<Row> rows =
        jdbc.sql(
                "SELECT * FROM public_app_setting ORDER BY group_order, group_id, position LIMIT :limit")
            .param("limit", MAX_SETTINGS + 1)
            .query(JdbcPublicAppSettings::row)
            .list();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    if (rows.size() > MAX_SETTINGS) {
      log.warn("the public app's published settings are more than {}: not shown", MAX_SETTINGS);
      return Optional.empty();
    }
    try {
      Row first = rows.getFirst();
      return Optional.of(new Publication(first.build(), first.publishedAt(), groups(rows)));
    } catch (IllegalArgumentException e) {
      log.warn("the public app's published settings make no group: not shown");
      return Optional.empty();
    }
  }

  private static List<SettingsGroup> groups(List<Row> rows) {
    Map<String, List<Row>> byGroup = new LinkedHashMap<>();
    for (Row row : rows) {
      byGroup.computeIfAbsent(row.groupId(), id -> new ArrayList<>()).add(row);
    }
    return byGroup.values().stream().map(JdbcPublicAppSettings::group).toList();
  }

  private static SettingsGroup group(List<Row> rows) {
    Row first = rows.getFirst();
    return new SettingsGroup(
        first.groupId(),
        first.groupTitle(),
        first.groupOrder(),
        rows.stream().map(Row::setting).toList());
  }

  private static Row row(ResultSet rs, int index) throws SQLException {
    return new Row(
        rs.getString("group_id"),
        rs.getString("group_title"),
        rs.getInt("group_order"),
        Setting.published(
            rs.getString("key"),
            rs.getString("label"),
            Setting.Kind.valueOf(rs.getString("kind")),
            rs.getString("value"),
            rs.getString("unit"),
            rs.getString("variable")),
        rs.getString("build"),
        rs.getTimestamp("published_at").toInstant());
  }

  private record Row(
      String groupId,
      String groupTitle,
      int groupOrder,
      Setting setting,
      String build,
      Instant publishedAt) {}
}
