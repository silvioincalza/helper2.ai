package ai.searchavo.catalog.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.catalog.ResourceRemoval;
import ai.searchavo.platform.AuditTrail;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class RemoveResourceTest {

  static final UUID ID = UUID.fromString("11111111-1111-5111-8111-111111111111");

  final List<UUID> removed = new ArrayList<>();
  final List<String> audited = new ArrayList<>();
  @Nullable String listed = "https://github.com/arkoscope/awesome-crypto-verified-sources";

  final ResourceRemoval removal =
      id -> {
        removed.add(id);
        return Optional.ofNullable(listed).map(ResourceRemoval.Removed::new);
      };

  final AuditTrail audit =
      new AuditTrail() {
        @Override
        public void record(
            String adminId, Action action, @Nullable String targetId, String outcome) {
          audited.add(adminId + " " + action + " " + targetId + " " + outcome);
        }

        @Override
        public void record(
            String adminId,
            Action action,
            @Nullable String targetId,
            String outcome,
            Change change) {
          audited.add(
              adminId
                  + " "
                  + action
                  + " "
                  + targetId
                  + " "
                  + outcome
                  + " | "
                  + change.before()
                  + " | "
                  + change.after()
                  + " | "
                  + change.reason());
        }
      };

  final RemoveResource remove = new RemoveResource(removal, audit);

  @Test
  void aListedResourceIsRemovedAndTheAuditKeepsItsAddressAndTheReason() {
    assertThat(remove.remove(ID, "  A crypto list, not about AI  ", "admin-1"))
        .isEqualTo(new RemoveResource.Outcome.Removed());
    assertThat(removed).containsExactly(ID);
    assertThat(audited)
        .containsExactly(
            "admin-1 REMOVE_RESOURCE "
                + ID
                + " REMOVED | https://github.com/arkoscope/awesome-crypto-verified-sources | null"
                + " | A crypto list, not about AI");
  }

  @Test
  void withoutAReasonNothingIsRemovedOrAudited() {
    assertThat(remove.remove(ID, "   ", "admin-1"))
        .isEqualTo(new RemoveResource.Outcome.NoReason(RemoveResource.MAX_REASON));
    assertThat(remove.remove(ID, null, "admin-1"))
        .isEqualTo(new RemoveResource.Outcome.NoReason(RemoveResource.MAX_REASON));
    assertThat(remove.remove(ID, "x".repeat(RemoveResource.MAX_REASON + 1), "admin-1"))
        .isEqualTo(new RemoveResource.Outcome.NoReason(RemoveResource.MAX_REASON));
    assertThat(removed).isEmpty();
    assertThat(audited).isEmpty();
  }

  @Test
  void anUnknownResourceIsNotFoundAndNotAudited() {
    listed = null;

    assertThat(remove.remove(ID, "not about AI", "admin-1"))
        .isEqualTo(new RemoveResource.Outcome.NoSuchResource());
    assertThat(audited).isEmpty();
  }

  @Test
  void uc013Br004AnAddressLongerThan100CharactersIsKeptWholeInTheAuditEntry() {
    listed = "https://example.com/" + "a".repeat(200);

    remove.remove(ID, "not about AI", "admin-1");

    String before = audited.getFirst().split(" \\| ")[1];
    assertThat(before).isEqualTo(listed);
  }

  @Test
  void uc013Br004AnAddressLongerThanAnAuditTextHoldsIsCutToItsLength() {
    listed = "https://example.com/" + "a".repeat(1200);

    remove.remove(ID, "not about AI", "admin-1");

    String before = audited.getFirst().split(" \\| ")[1];
    assertThat(before).hasSize(1000).endsWith("…");
  }
}
