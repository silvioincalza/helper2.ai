package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.AwesomeListIndex;
import ai.helper2.catalog.AwesomeListText;
import ai.helper2.catalog.GoneLists;
import ai.helper2.catalog.ListNamings;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceId;
import ai.helper2.testing.AsBackOffice;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AsBackOffice
class JdbcGoneListsIT {

  static final int VISITS = 3;

  @Autowired GoneLists gone;
  @Autowired AwesomeListIndex index;
  @Autowired ListNamings namings;
  @Autowired JdbcClient jdbc;

  private final String run = UUID.randomUUID().toString().substring(0, 8);
  private final String list = "https://github.com/acme/awesome-" + run;
  private final String named = "https://github.com/acme/named-" + run;
  private final List<UUID> created = new ArrayList<>();

  @AfterEach
  void clean() {
    created.forEach(id -> jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", id).update());
    jdbc.sql("DELETE FROM source WHERE id = 'aa-gone-lists'").update();
  }

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
    created.add(idOf(url));
  }

  private UUID recordOf(String url, @Nullable String repositoryUrl, String vitality) {
    jdbc.sql(
            "INSERT INTO source (id, mode) VALUES ('aa-gone-lists', 'INDEXED') ON CONFLICT DO NOTHING")
        .update();
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url,
                                       repository_url, title, type, retrieved_at, fingerprint, extra)
            VALUES (:id, 'aa-gone-lists', :id::text, :rid, :url, :url, :repository, 'Listed', 'LIBRARY', now(),
                    'fp', jsonb_build_object('vitality', CAST(:vitality AS jsonb)))
            """)
        .param("id", id)
        .param("rid", idOf(url))
        .param("url", url)
        .param("repository", repositoryUrl)
        .param("vitality", vitality)
        .update();
    return id;
  }

  private UUID aListThatNamesOneResource(String vitalityOfItsRecord) {
    List.of(list, named).forEach(this::resource);
    index.replace(
        new AwesomeListText(
            CanonicalUrl.of(list),
            "Agents",
            List.of(new AwesomeListText.Entry(CanonicalUrl.of(named), "a tool", "Agents"))));
    return recordOf(list, null, vitalityOfItsRecord);
  }

  private int entries() {
    return jdbc.sql("SELECT count(*) FROM awesome_list_entry WHERE list_id = :list")
        .param("list", idOf(list))
        .query(Integer.class)
        .single();
  }

  private boolean listRow() {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM awesome_list WHERE list_id = :list)")
        .param("list", idOf(list))
        .query(Boolean.class)
        .single();
  }

  // UC-030 BR-010: a list gone at its source on the set number of visits names nothing any more.
  @Test
  void aListGoneOnEnoughVisitsInARowNoLongerNamesItsResources() {
    UUID record = aListThatNamesOneResource("{\"gone\":true,\"notFoundVisits\":3}");

    GoneLists.Forgotten forgotten = gone.forget(List.of(record), VISITS);

    assertThat(forgotten.lists()).isEqualTo(1);
    assertThat(forgotten.noLongerNamed()).containsExactly(idOf(named));
    assertThat(entries()).isZero();
    assertThat(listRow()).isFalse();
  }

  // UC-030 BR-010: the list's own resource follows the rule of a repository that is gone
  // (UC-018 BR-009); it is not deleted here.
  @Test
  void theOwnResourceOfAGoneListStays() {
    UUID record = aListThatNamesOneResource("{\"gone\":true,\"notFoundVisits\":3}");

    gone.forget(List.of(record), VISITS);

    assertThat(
            jdbc.sql("SELECT count(*) FROM resource WHERE id IN (:list, :named)")
                .param("list", idOf(list))
                .param("named", idOf(named))
                .query(Integer.class)
                .single())
        .isEqualTo(2);
  }

  // UC-030 BR-010: fewer visits than the set number remove nothing.
  @Test
  void aListNotFoundOnTooFewVisitsKeepsWhatItNamed() {
    UUID record = aListThatNamesOneResource("{\"gone\":true,\"notFoundVisits\":2}");

    GoneLists.Forgotten forgotten = gone.forget(List.of(record), VISITS);

    assertThat(forgotten.lists()).isZero();
    assertThat(forgotten.noLongerNamed()).isEmpty();
    assertThat(entries()).isEqualTo(1);
    assertThat(listRow()).isTrue();
  }

  // UC-030 BR-010: visits inside one day do not make a repository gone (UC-018 BR-009), so they
  // remove nothing, however many they are.
  @Test
  void aListNotFoundManyTimesButNotConfirmedGoneKeepsWhatItNamed() {
    UUID record =
        aListThatNamesOneResource("{\"notFoundAt\":\"2026-01-01T00:00:00Z\",\"notFoundVisits\":9}");

    gone.forget(List.of(record), VISITS);

    assertThat(entries()).isEqualTo(1);
  }

  // UC-030 BR-010: only the record that is the repository of the list counts, never a record that
  // links it.
  @Test
  void aRecordThatOnlyLinksAGoneRepositoryRemovesNothing() {
    List.of(list, named).forEach(this::resource);
    index.replace(
        new AwesomeListText(
            CanonicalUrl.of(list),
            "Agents",
            List.of(new AwesomeListText.Entry(CanonicalUrl.of(named), "a tool", "Agents"))));
    UUID linking =
        recordOf(list, "https://github.com/acme/elsewhere", "{\"gone\":true,\"notFoundVisits\":3}");

    gone.forget(List.of(linking), VISITS);

    assertThat(entries()).isEqualTo(1);
  }

  // UC-030 BR-010: a candidate the list proposed, accepted after the list is gone, is not named
  // by it; a list that is read again names it.
  @Test
  void aListGoneAtItsSourceDoesNotNameACandidateAcceptedLater() {
    UUID record = aListThatNamesOneResource("{\"gone\":true,\"notFoundVisits\":3}");
    gone.forget(List.of(record), VISITS);

    boolean named =
        namings.byCuratedList(CanonicalUrl.of(list), idOf(this.named), "a tool", "Agents");

    assertThat(named).isFalse();
    assertThat(entries()).isZero();
  }

  // UC-030 BR-010: only the lists whose record was not found at this visit are looked at.
  @Test
  void aGoneListWhoseRecordIsNotAmongTheOnesAskedKeepsWhatItNamed() {
    aListThatNamesOneResource("{\"gone\":true,\"notFoundVisits\":3}");

    gone.forget(List.of(UUID.randomUUID()), VISITS);

    assertThat(entries()).isEqualTo(1);
  }
}
