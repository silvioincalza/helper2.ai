package ai.helper2.scout.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.identity.AuditLog;
import ai.helper2.platform.AuditTrail;
import ai.helper2.scout.GoneListSeeds;
import ai.helper2.scout.ListKind;
import ai.helper2.scout.ListOrigin;
import ai.helper2.scout.ListReading;
import ai.helper2.scout.ListSeed;
import ai.helper2.scout.ListSeedStore;
import ai.helper2.scout.usecase.ForgetGoneListSeed;
import ai.helper2.shared.UnitOfWork;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * UC-030 BR-012: a list discovery added that is gone at its site leaves the lists to read with one
 * audit entry in the name of the system, in one transaction.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ForgetGoneListSeedIT {

  static final Instant FIRST_CHECK = Instant.parse("2026-11-20T03:07:00Z");

  final String page = "https://it-gone.acme.dev/lists/" + UUID.randomUUID();

  @Autowired GoneListSeeds records;
  @Autowired ListSeedStore store;
  @Autowired AuditLog log;
  @Autowired UnitOfWork unitOfWork;
  @Autowired JdbcClient jdbc;

  @AfterEach
  void clean() {
    store.remove(page);
  }

  void goneOnThreeChecks(ListOrigin origin) {
    store.add(
        new ListSeed(
            ListKind.WEB_PAGE, page, "discovery-coding", FIRST_CHECK, null, null, 0, origin));
    store.readSucceeded(page, new ListReading(FIRST_CHECK, List.of(), List.of(), List.of(), 0, 0));
    store.goneChecked(page, FIRST_CHECK);
    store.goneChecked(page, FIRST_CHECK.plus(1, ChronoUnit.HOURS));
    store.goneChecked(page, FIRST_CHECK.plus(25, ChronoUnit.HOURS));
  }

  List<Map<String, Object>> auditRows() {
    return jdbc.sql(
            """
            SELECT admin_id, action, outcome, before_value, after_value, reason
            FROM admin_audit WHERE target_id = :page
            """)
        .param("page", page)
        .query()
        .listOfRows();
  }

  boolean isAListToRead() {
    return store.all().stream().anyMatch(s -> s.getAddress().equals(page));
  }

  @Test
  void uc030Br012TheRemovalOfADiscoveredListThatIsGoneIsAuditedInTheNameOfTheSystem() {
    goneOnThreeChecks(ListOrigin.DISCOVERY);

    records.forget(page);

    assertThat(isAListToRead()).isFalse();
    assertThat(auditRows()).hasSize(1);
    Map<String, Object> row = auditRows().getFirst();
    assertThat(row.get("admin_id")).isEqualTo(AuditTrail.SYSTEM);
    assertThat(row.get("action")).isEqualTo("REMOVE_GONE_LIST_SEED");
    assertThat(row.get("outcome")).isEqualTo("REMOVED");
    assertThat(row.get("before_value")).isNull();
    assertThat(row.get("after_value")).isNull();
    assertThat(row.get("reason"))
        .isEqualTo("3 checks in a row said the page is gone, the first at 2026-11-20T03:07:00Z");
  }

  // UC-013 BR-004: the audit page reads an entry of the system like any other.
  @Test
  void uc013Br004TheAuditLogListsAnEntryOfTheSystem() {
    goneOnThreeChecks(ListOrigin.DISCOVERY);

    records.forget(page);

    assertThat(log.page(0, 100).items())
        .anySatisfy(entry -> {
          assertThat(entry.targetId()).isEqualTo(page);
          assertThat(entry.adminId()).isEqualTo(AuditTrail.SYSTEM);
          assertThat(entry.action()).isEqualTo(AuditTrail.Action.REMOVE_GONE_LIST_SEED);
        });
  }

  @Test
  void uc030Br012AGoneListAnAdministratorAddedStaysAndGetsNoAuditEntry() {
    goneOnThreeChecks(ListOrigin.ADMINISTRATOR);

    records.forget(page);

    assertThat(isAListToRead()).isTrue();
    assertThat(auditRows()).isEmpty();
  }

  @Test
  void uc030Br012AnAuditEntryThatCannotBeWrittenLeavesTheListAndItsLastRead() {
    goneOnThreeChecks(ListOrigin.DISCOVERY);
    AuditTrail down =
        (adminId, action, targetId, outcome) -> {
          throw new IllegalStateException("audit down");
        };

    assertThatThrownBy(() -> new ForgetGoneListSeed(store, down, unitOfWork).forget(page))
        .isInstanceOf(IllegalStateException.class);

    assertThat(isAListToRead()).isTrue();
    assertThat(store.lastReads()).containsKey(page);
    assertThat(auditRows()).isEmpty();
  }
}
