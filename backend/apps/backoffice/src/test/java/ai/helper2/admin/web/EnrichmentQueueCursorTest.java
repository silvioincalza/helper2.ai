package ai.helper2.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.catalog.enrichment.EnrichmentCandidates.BacklogCursor;
import ai.helper2.catalog.enrichment.EnrichmentCandidates.Part;
import ai.helper2.platform.BadRequestException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnrichmentQueueCursorTest {

  @Test
  void aCursorOfEveryPartComesBackAsItWent() {
    for (Part part : Part.values()) {
      BacklogCursor cursor =
          new BacklogCursor(
              part,
              new BigDecimal("0.656"),
              Instant.parse("2026-11-05T10:15:30.123456Z"),
              UUID.randomUUID());
      String token = EnrichmentQueueController.encode(cursor);

      assertThat(token).matches("[A-Za-z0-9_-]+");
      assertThat(EnrichmentQueueController.decode(token)).isEqualTo(cursor);
    }
  }

  @Test
  void anythingElseIsABadRequest() {
    String wrongPart =
        Base64.getUrlEncoder()
            .encodeToString(("X|0|2026-11-05T10:15:30Z|" + UUID.randomUUID()).getBytes());
    String farFuture =
        Base64.getUrlEncoder()
            .encodeToString(("N|0|+1000000000-01-01T00:00:00Z|" + UUID.randomUUID()).getBytes());
    String beforeEpoch =
        Base64.getUrlEncoder()
            .encodeToString(("R|0|-5000-01-01T00:00:00Z|" + UUID.randomUUID()).getBytes());
    String noPopularity =
        Base64.getUrlEncoder()
            .encodeToString(("N|2026-11-05T10:15:30Z|" + UUID.randomUUID()).getBytes());
    String morePopularThanAny =
        Base64.getUrlEncoder()
            .encodeToString(("N|1.5|2026-11-05T10:15:30Z|" + UUID.randomUUID()).getBytes());
    String notANumber =
        Base64.getUrlEncoder()
            .encodeToString(("N|top|2026-11-05T10:15:30Z|" + UUID.randomUUID()).getBytes());
    for (String token :
        new String[] {
          "!!",
          "",
          wrongPart,
          "Tnx4eHg",
          farFuture,
          beforeEpoch,
          noPopularity,
          morePopularThanAny,
          notANumber
        }) {
      assertThatThrownBy(() -> EnrichmentQueueController.decode(token))
          .isInstanceOf(BadRequestException.class);
    }
  }
}
