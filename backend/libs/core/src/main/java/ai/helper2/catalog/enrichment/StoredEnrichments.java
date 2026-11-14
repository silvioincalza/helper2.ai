package ai.helper2.catalog.enrichment;

import ai.helper2.shared.ValueOrigin;
import ai.helper2.shared.Verification;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface StoredEnrichments {
  record StoredField(
      UUID id,
      String field,
      String value,
      ValueOrigin origin,
      Verification verification,
      List<String> excerpts) {}

  record StoredClaim(UUID id, String text, List<String> excerpts) {}

  record Stored(
      UUID resourceId,
      String title,
      String type,
      @Nullable String promptVersion,
      List<StoredField> fields,
      List<StoredClaim> claims) {}

  List<UUID> judgedAfter(@Nullable UUID after, int limit);

  Stored stored(UUID resourceId);

  int rejectFields(List<UUID> fieldIds);

  int dropClaims(List<UUID> claimIds);

  void requestRun(UUID resourceId);

  int closeItemsOfUnstatedValues(String by, String note);

  boolean done(String name);

  void markDone(String name);
}
