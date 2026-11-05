package ai.helper2.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.catalog.enrichment.EnrichmentCandidates.BacklogCursor;
import ai.helper2.platform.BadRequestException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnrichmentQueueCursorTest {

  @Test
  void aCursorComesBackAsItWent() {
    BacklogCursor cursor =
        new BacklogCursor(false, Instant.parse("2026-11-05T10:15:30.123456Z"), UUID.randomUUID());
    String token = EnrichmentQueueController.encode(cursor);

    assertThat(token).matches("[A-Za-z0-9_-]+");
    assertThat(EnrichmentQueueController.decode(token)).isEqualTo(cursor);
  }

  @Test
  void anythingElseIsABadRequest() {
    String wrongPart =
        Base64.getUrlEncoder()
            .encodeToString(("X|2026-11-05T10:15:30Z|" + UUID.randomUUID()).getBytes());
    String farFuture =
        Base64.getUrlEncoder()
            .encodeToString(("N|+1000000000-01-01T00:00:00Z|" + UUID.randomUUID()).getBytes());
    String beforeEpoch =
        Base64.getUrlEncoder()
            .encodeToString(("R|-5000-01-01T00:00:00Z|" + UUID.randomUUID()).getBytes());
    for (String token : new String[] {"!!", "", wrongPart, "Tnx4eHg", farFuture, beforeEpoch}) {
      assertThatThrownBy(() -> EnrichmentQueueController.decode(token))
          .isInstanceOf(BadRequestException.class);
    }
  }
}
