package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.AwesomeListEntries;
import ai.helper2.catalog.DirectoryListIndex;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceId;
import ai.helper2.testing.AsBackOffice;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AsBackOffice
class JdbcDirectoryListIndexIT {

  @Autowired DirectoryListIndex index;
  @Autowired AwesomeListEntries entries;
  @Autowired JdbcClient jdbc;

  private final String run = UUID.randomUUID().toString().substring(0, 8);
  private final String list = "https://directory-" + run + ".example/best-ai-coding-tools";
  private final String named = "https://github.com/acme/named-" + run;
  private final String unknown = "https://github.com/acme/unknown-" + run;

  private static UUID idOf(String url) {
    return ResourceId.of(CanonicalUrl.of(url)).getValue();
  }

  @AfterEach
  void clean() {
    jdbc.sql("DELETE FROM directory_list_entry WHERE list_url = :url").param("url", list).update();
    jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", idOf(named)).update();
  }

  private void resource(String url) {
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
            VALUES (:id, :url, :url, 'Listed', 'SOURCE_PROVIDED', 'LIBRARY', 'BUILD_WITH_AI', 'INDEXED')
            """)
        .param("id", idOf(url))
        .param("url", CanonicalUrl.of(url).getValue())
        .update();
  }

  private DirectoryListIndex.DirectoryList naming(String... urls) {
    return new DirectoryListIndex.DirectoryList(
        CanonicalUrl.of(list),
        "Best AI coding tools",
        List.of(urls).stream()
            .map(url -> new DirectoryListIndex.Entry(CanonicalUrl.of(url), "Editors"))
            .toList());
  }

  @Test
  void uc030Br009AListPageOfADirectoryHostNamesItsResourcesAndIsNoResourceItself() {
    resource(named);

    index.replace(naming(named, unknown));

    assertThat(entries.directoryListsNaming(idOf(named)))
        .containsExactly(
            new AwesomeListEntries.DirectoryList("Best AI coding tools", list, "Editors"));
    assertThat(entries.naming(idOf(named), 10).directoryLists()).isEqualTo(1);
    assertThat(entries.naming(idOf(named), 10).lists()).isEmpty();
    assertThat(
            jdbc.sql("SELECT count(*) FROM resource WHERE canonical_url = :url OR url = :url")
                .param("url", list)
                .query(Integer.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql("SELECT count(*) FROM awesome_list_entry WHERE resource_id = :id")
                .param("id", idOf(named))
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  void uc030Br008AReadReplacesWhatTheListNamed() {
    resource(named);
    index.replace(naming(named));

    index.replace(naming());

    assertThat(entries.naming(idOf(named), 10).directoryLists()).isZero();

    index.replace(naming(named));

    assertThat(entries.directoryListsNaming(idOf(named))).hasSize(1);
  }

  @Test
  void uc030Br008WhatAListNamedGoesWithTheResourceItNamed() {
    resource(named);
    index.replace(naming(named));

    jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", idOf(named)).update();

    assertThat(
            jdbc.sql("SELECT count(*) FROM directory_list_entry WHERE list_url = :url")
                .param("url", list)
                .query(Integer.class)
                .single())
        .isZero();
  }
}
