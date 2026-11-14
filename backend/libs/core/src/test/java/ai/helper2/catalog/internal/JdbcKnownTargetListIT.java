package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.KnownTarget;
import ai.helper2.catalog.KnownTargetList;
import ai.helper2.testing.AsBackOffice;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AsBackOffice
class JdbcKnownTargetListIT {

  static final Instant ADDED = Instant.parse("2026-01-02T03:04:05Z");

  @Autowired KnownTargetList targets;

  private final String run = UUID.randomUUID().toString().substring(0, 8);
  private final String name = "Acme " + run;
  private final String otherName = "Acme Cloud " + run;

  private final List<UUID> addedHere = new ArrayList<>();

  @AfterEach
  void removeWhatThisTestAdded() {
    addedHere.forEach(targets::remove);
  }

  private UUID added(KnownTarget.Kind kind, String... names) {
    UUID id = UUID.randomUUID();
    addedHere.add(id);
    targets.add(id, kind, List.of(names), "owner", ADDED, KnownTarget.Origin.CURATED);
    return id;
  }

  @Test
  void anAddedTargetIsFoundWithItsOtherNames() {
    UUID id = added(KnownTarget.Kind.VENDOR, name, otherName);

    KnownTargetList.Entry entry = targets.find(id);

    assertThat(entry).isNotNull();
    assertThat(entry.kind()).isEqualTo(KnownTarget.Kind.VENDOR);
    assertThat(entry.name()).isEqualTo(name);
    assertThat(entry.otherNames()).containsExactly(otherName);
    assertThat(entry.addedBy()).isEqualTo("owner");
    assertThat(entry.addedAt()).isEqualTo(ADDED);
    assertThat(entry.relationships()).isZero();
    assertThat(targets.all()).extracting(KnownTargetList.Entry::id).contains(id);
  }

  @Test
  void everyNameOfATargetIsHeldByIt() {
    UUID id = added(KnownTarget.Kind.SERVICE, name, otherName);

    assertThat(targets.holders(List.of(KnownTarget.key(name), KnownTarget.key(otherName))))
        .containsEntry(KnownTarget.key(name), id)
        .containsEntry(KnownTarget.key(otherName), id);
    assertThat(targets.holders(List.of())).isEmpty();
  }

  @Test
  void aReplacedTargetHasItsNewKindAndNamesOnly() {
    UUID id = added(KnownTarget.Kind.VENDOR, name, otherName);
    String renamed = "Acme AI " + run;

    targets.replace(id, KnownTarget.Kind.SERVICE, List.of(renamed));

    KnownTargetList.Entry entry = targets.find(id);
    assertThat(entry.kind()).isEqualTo(KnownTarget.Kind.SERVICE);
    assertThat(entry.name()).isEqualTo(renamed);
    assertThat(entry.otherNames()).isEmpty();
    assertThat(targets.holders(List.of(KnownTarget.key(name), KnownTarget.key(renamed))))
        .containsOnlyKeys(KnownTarget.key(renamed));
  }

  @Test
  void aRemovedTargetTakesItsNamesWithIt() {
    UUID id = added(KnownTarget.Kind.VENDOR, name, otherName);

    targets.remove(id);

    assertThat(targets.find(id)).isNull();
    assertThat(targets.holders(List.of(KnownTarget.key(name), KnownTarget.key(otherName))))
        .isEmpty();
  }
}
