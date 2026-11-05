package ai.helper2.admin.web;

import ai.helper2.admin.usecase.ListEnrichmentQueue;
import ai.helper2.api.generated.EnrichmentQueueApi;
import ai.helper2.api.generated.model.EnrichmentQueueEntryDto;
import ai.helper2.api.generated.model.EnrichmentQueuePageDto;
import ai.helper2.catalog.enrichment.EnrichmentCandidates.BacklogCursor;
import ai.helper2.catalog.enrichment.EnrichmentCandidates.BacklogEntry;
import ai.helper2.platform.BadRequestException;
import ai.helper2.shared.UtcTimes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/v1/admin/enrichment-queue (ADMIN only, admin port): the backlog, page by page. */
@RestController
@RequestMapping("/api/v1")
class EnrichmentQueueController implements EnrichmentQueueApi {

  private final ListEnrichmentQueue queue;

  EnrichmentQueueController(ListEnrichmentQueue queue) {
    this.queue = queue;
  }

  @Override
  public ResponseEntity<EnrichmentQueuePageDto> listEnrichmentQueue(
      @Nullable String after, Integer size) {
    ListEnrichmentQueue.Page page = queue.list(after == null ? null : decode(after), size);
    return ResponseEntity.ok(
        new EnrichmentQueuePageDto(
                page.items().stream().map(EnrichmentQueueController::entry).toList())
            .next(page.next() == null ? null : encode(page.next())));
  }

  private static EnrichmentQueueEntryDto entry(BacklogEntry e) {
    return new EnrichmentQueueEntryDto(
            e.id(),
            e.title(),
            EnrichmentQueueEntryDto.WaitingEnum.fromValue(e.waiting().name()),
            UtcTimes.odt(e.firstDiscoveredAt()),
            e.failedRuns())
        .lastOutcome(
            e.lastOutcome() == null
                ? null
                : EnrichmentQueueEntryDto.LastOutcomeEnum.fromValue(e.lastOutcome()))
        .lastRunAt(e.lastRunAt() == null ? null : UtcTimes.odt(e.lastRunAt()));
  }

  /** The cursor is opaque to the page: the part, the discovery time and the id, URL-safe. */
  static String encode(BacklogCursor cursor) {
    String plain =
        (cursor.neverRun() ? "N" : "R") + "|" + cursor.firstDiscoveredAt() + "|" + cursor.id();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
  }

  static BacklogCursor decode(String cursor) {
    try {
      String[] parts =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
              .split("\\|", -1);
      if (parts.length != 3 || !(parts[0].equals("N") || parts[0].equals("R"))) {
        throw new IllegalArgumentException("parts");
      }
      return new BacklogCursor(
          parts[0].equals("N"), Instant.parse(parts[1]), UUID.fromString(parts[2]));
    } catch (RuntimeException unreadable) {
      throw new BadRequestException("after is not a cursor from this queue");
    }
  }
}
