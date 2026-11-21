package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.AwesomeListIndex;
import ai.helper2.catalog.AwesomeListText;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceId;
import ai.helper2.testing.AsBackOffice;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AsBackOffice
class JdbcAwesomeListIndexIT {

  @Autowired AwesomeListIndex index;
  @Autowired JdbcClient jdbc;

  private final String run = UUID.randomUUID().toString().substring(0, 8);
  private final String list = "https://github.com/acme/awesome-" + run;
  private final String kept = "https://github.com/acme/kept-" + run;
  private final String dropped = "https://github.com/acme/dropped-" + run;
  private final String added = "https://github.com/acme/added-" + run;

  private static UUID idOf(String url) {
    return ResourceId.of(CanonicalUrl.of(url)).getValue();
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

  private AwesomeListText naming(String... urls) {
    return new AwesomeListText(
        CanonicalUrl.of(list),
        "Agents",
        List.of(urls).stream()
            .map(url -> new AwesomeListText.Entry(CanonicalUrl.of(url), "a tool", "Agents"))
            .toList());
  }

  private List<UUID> entries() {
    return jdbc.sql("SELECT resource_id FROM awesome_list_entry WHERE list_id = :list")
        .param("list", idOf(list))
        .query(UUID.class)
        .list();
  }

  @Test
  void replacingAListTellsWhichResourcesItNoLongerNames() {
    List.of(list, kept, dropped, added).forEach(this::resource);
    AwesomeListIndex.Replaced first = index.replace(naming(kept, dropped));
    assertThat(first.written()).containsExactlyInAnyOrder(idOf(kept), idOf(dropped));
    assertThat(first.removed()).isEmpty();

    AwesomeListIndex.Replaced second = index.replace(naming(kept, added));

    assertThat(second.written()).containsExactlyInAnyOrder(idOf(kept), idOf(added));
    assertThat(second.removed()).containsExactly(idOf(dropped));
    assertThat(second.touched()).containsExactlyInAnyOrder(idOf(kept), idOf(added), idOf(dropped));
    assertThat(entries()).containsExactlyInAnyOrder(idOf(kept), idOf(added));
  }

  @Test
  void aListLeftEmptyNamesEveryResourceItHad() {
    List.of(list, kept).forEach(this::resource);
    index.replace(naming(kept));

    AwesomeListIndex.Replaced emptied = index.replace(naming());

    assertThat(emptied.written()).isEmpty();
    assertThat(emptied.removed()).containsExactly(idOf(kept));
    assertThat(entries()).isEmpty();
  }
}
