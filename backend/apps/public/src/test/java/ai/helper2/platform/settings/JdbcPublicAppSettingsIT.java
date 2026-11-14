package ai.helper2.platform.settings;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.platform.settings.PublicAppSettings.Publication;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(
    properties = {
      "helper2.quota.key-secret=" + JdbcPublicAppSettingsIT.QUOTA_SECRET,
      "helper2.setup.github-token=" + JdbcPublicAppSettingsIT.GITHUB_TOKEN,
      "helper2.setup.hosts.hf-token=" + JdbcPublicAppSettingsIT.HF_TOKEN,
    })
class JdbcPublicAppSettingsIT {

  static final Instant AT = Instant.parse("2026-11-11T10:00:00Z");

  static final SettingsGroup GUIDE =
      new SettingsGroup(
          "guide",
          "AI guide",
          SettingsGroup.GUIDE,
          List.of(
              Setting.count(
                  "guide.maxCandidates", "Results", 12, "results", "H2_GUIDE_MAX_CANDIDATES"),
              Setting.model("guide.planModel", "Plan model", null, null)));
  static final SettingsGroup SETUP =
      new SettingsGroup(
          "setup",
          "Setup guide",
          SettingsGroup.SETUP,
          List.of(Setting.flag("setup.reasoning", "Reasoning", true, "H2_SETUP_GUIDE_REASONING")));

  static final String QUOTA_SECRET = "fake-quota-secret-8d1e";
  static final String GITHUB_TOKEN = "ghp_fakeGithubToken4c2a";
  static final String HF_TOKEN = "hf_fakeHuggingFaceToken9b7";

  @Autowired PublicAppSettings published;

  @Autowired List<SettingsGroup> groups;

  @Autowired JdbcClient jdbc;

  @Test
  void noSecretOfThePublicAppIsPublished() {
    published.publish("2026.11.1", AT, groups);

    String everything =
        String.join(
            "\n", jdbc.sql("SELECT t::text FROM public_app_setting t").query(String.class).list());
    assertThat(groups).extracting(SettingsGroup::id).contains("guide", "setup");
    assertThat(everything)
        .contains("guide.maxCandidates")
        .doesNotContain(QUOTA_SECRET, GITHUB_TOKEN, HF_TOKEN)
        .doesNotContainIgnoringCase("api-key")
        .doesNotContain("@");
  }

  @Test
  void whatThePublicAppPublishesIsReadBackInPageOrder() {
    published.publish("2026.11.1", AT, List.of(SETUP, GUIDE));

    assertThat(published.newest())
        .contains(new Publication("2026.11.1", AT, List.of(GUIDE, SETUP)));
  }

  @Test
  void aNewStartReplacesTheWholePublication() {
    published.publish("2026.11.1", AT, List.of(GUIDE, SETUP));
    published.publish("2026.11.2", AT.plusSeconds(60), List.of(SETUP));

    assertThat(published.newest())
        .contains(new Publication("2026.11.2", AT.plusSeconds(60), List.of(SETUP)));
  }

  @Test
  void aStoredValueThePageCannotShowIsHidden() {
    published.publish("2026.11.1", AT, List.of(GUIDE));
    jdbc.sql("DELETE FROM public_app_setting WHERE key = 'guide.planModel'").update();
    jdbc.sql(
            """
            INSERT INTO public_app_setting
              (key, group_id, group_title, group_order, position, label, kind, value, build, published_at)
            SELECT 'guide.planModel', group_id, group_title, group_order, 1, 'Plan model', 'MODEL',
                   'postgres://user:pw@host/db', build, published_at
            FROM public_app_setting WHERE key = 'guide.maxCandidates'
            """)
        .update();

    assertThat(published.newest().orElseThrow().groups().getFirst().settings())
        .extracting(Setting::value)
        .containsExactly("12", Setting.HIDDEN);
  }

  @Test
  void moreRowsThanAPageOfSettingsReadAsNothingPublished() {
    List<Setting> many =
        java.util.stream.IntStream.rangeClosed(0, JdbcPublicAppSettings.MAX_SETTINGS)
            .mapToObj(i -> Setting.flag("guide.flag" + i, "Flag", true, null))
            .toList();

    published.publish(
        "2026.11.1",
        AT,
        List.of(new SettingsGroup("guide", "AI guide", SettingsGroup.GUIDE, many)));

    assertThat(published.newest()).isEmpty();
  }

  @Test
  void rowsThatMakeNoGroupReadAsNothingPublished() {
    published.publish("2026.11.1", AT, List.of(GUIDE));
    jdbc.sql("DELETE FROM public_app_setting WHERE key = 'guide.planModel'").update();
    jdbc.sql(
            """
            INSERT INTO public_app_setting
              (key, group_id, group_title, group_order, position, label, kind, build, published_at)
            SELECT 'other.key', group_id, group_title, group_order, 1, 'Other', 'MODEL', build, published_at
            FROM public_app_setting WHERE key = 'guide.maxCandidates'
            """)
        .update();

    assertThat(published.newest()).isEmpty();
  }
}
