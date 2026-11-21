package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class ResourceFoundInApiIT extends ApiTestBase {

  final JdbcClient owner = TestcontainersConfiguration.ownerJdbc();
  final String run = UUID.randomUUID().toString().substring(0, 8);
  final List<UUID> lists = new ArrayList<>();

  @AfterEach
  void removeTheLists() {
    owner
        .sql("DELETE FROM resource WHERE id = ANY(:lists)")
        .param("lists", lists.toArray(UUID[]::new))
        .update();
  }

  private UUID curated(String canonicalUrl) {
    return owner
        .sql("SELECT id FROM resource WHERE canonical_url = :url")
        .param("url", canonicalUrl)
        .query(UUID.class)
        .single();
  }

  private UUID list(String name, int popularity) {
    String url = CanonicalUrl.of("https://github.com/acme/" + name + "-" + run).getValue();
    UUID id = ResourceId.of(CanonicalUrl.of(url)).getValue();
    owner
        .sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin,
                                  popularity)
            VALUES (:id, :url, :url, :title, 'SOURCE_PROVIDED', 'ARTICLE', 'BUILD_WITH_AI', 'INDEXED', :popularity)
            """)
        .param("id", id)
        .param("url", url)
        .param("title", "acme/" + name)
        .param("popularity", popularity)
        .update();
    lists.add(id);
    return id;
  }

  private void names(UUID list, UUID resource, String headingPath) {
    owner
        .sql(
            """
            INSERT INTO awesome_list_entry (list_id, resource_id, heading_path, search_vector)
            VALUES (:list, :resource, :heading, to_tsvector('english', :heading))
            """)
        .param("list", list)
        .param("resource", resource)
        .param("heading", headingPath)
        .update();
  }

  @Test
  void aResourceInTwoListsNamesBothWithTheirHeadingPathsTheMostPopularFirst() throws Exception {
    UUID resource = curated("https://deepl.com/translator");
    UUID small = list("awesome-translation", 1200);
    UUID big = list("awesome-ai-tools", 54000);
    names(small, resource, "Apps > Translation");
    names(big, resource, "");

    String body =
        mvc.perform(get("/api/v1/resources/" + resource))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.foundIn.lists", hasSize(2)))
            .andExpect(jsonPath("$.foundIn.lists[0].listId").value(big.toString()))
            .andExpect(jsonPath("$.foundIn.lists[0].listTitle").value("acme/awesome-ai-tools"))
            .andExpect(jsonPath("$.foundIn.lists[0].headingPath").value(""))
            .andExpect(jsonPath("$.foundIn.lists[1].listId").value(small.toString()))
            .andExpect(jsonPath("$.foundIn.lists[1].listTitle").value("acme/awesome-translation"))
            .andExpect(jsonPath("$.foundIn.lists[1].headingPath").value("Apps > Translation"))
            .andExpect(jsonPath("$.foundIn.more").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("ResourceDetail", body);
  }

  @Test
  void listsOfTheSamePopularityAreInTheOrderOfTheirTitles() throws Exception {
    UUID resource = curated("https://deepl.com/translator");
    names(list("awesome-zeta", 300), resource, "Tools");
    names(list("awesome-alpha", 300), resource, "Tools");

    mvc.perform(get("/api/v1/resources/" + resource))
        .andExpect(jsonPath("$.foundIn.lists[0].listTitle").value("acme/awesome-alpha"))
        .andExpect(jsonPath("$.foundIn.lists[1].listTitle").value("acme/awesome-zeta"));
  }

  @Test
  void aResourceInTwelveListsNamesTheTenMostPopularAndCountsTheRest() throws Exception {
    UUID resource = curated("https://deepl.com/translator");
    IntStream.rangeClosed(1, 12)
        .forEach(n -> names(list("awesome-%02d".formatted(n), n * 100), resource, "Tools"));

    mvc.perform(get("/api/v1/resources/" + resource))
        .andExpect(jsonPath("$.foundIn.lists", hasSize(10)))
        .andExpect(jsonPath("$.foundIn.lists[0].listTitle").value("acme/awesome-12"))
        .andExpect(jsonPath("$.foundIn.lists[9].listTitle").value("acme/awesome-03"))
        .andExpect(jsonPath("$.foundIn.more").value(2));
  }

  @Test
  void aResourceInNoListNamesNone() throws Exception {
    String body =
        mvc.perform(get("/api/v1/resources/" + curated("https://deepl.com/translator")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.foundIn.lists", hasSize(0)))
            .andExpect(jsonPath("$.foundIn.more").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("ResourceDetail", body);
  }

  @Test
  void uc007Br008AListOnADirectoryHostIsCountedAndNeverNamed() throws Exception {
    UUID resource = curated("https://deepl.com/translator");
    UUID curatedList = list("awesome-translation", 1200);
    UUID directoryList = list("best-translation-apps", 90000);
    names(curatedList, resource, "Apps");
    names(directoryList, resource, "Top 10");
    owner
        .sql(
            """
            INSERT INTO awesome_list (list_id, search_vector, directory)
            VALUES (:list, to_tsvector('english', 'best translation apps'), true)
            """)
        .param("list", directoryList)
        .update();

    String body =
        mvc.perform(get("/api/v1/resources/" + resource))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.foundIn.lists", hasSize(1)))
            .andExpect(jsonPath("$.foundIn.lists[0].listId").value(curatedList.toString()))
            .andExpect(jsonPath("$.foundIn.more").value(0))
            .andExpect(jsonPath("$.foundIn.directoryLists").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("best-translation-apps");
  }
}
