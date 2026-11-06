package ai.helper2.admin.web;

import ai.helper2.admin.usecase.ListEnrichmentQueue;
import ai.helper2.api.generated.EnrichmentQueueApi;
import ai.helper2.api.generated.model.EnrichmentQueueEntryDto;
import ai.helper2.api.generated.model.EnrichmentQueuePageDto;
import ai.helper2.api.generated.model.EnrichmentQueueViewDto;
import ai.helper2.api.generated.model.EnrichmentSkipDto;
import ai.helper2.catalog.enrichment.EnrichmentCandidates.BacklogCursor;
import ai.helper2.catalog.enrichment.EnrichmentCandidates.BacklogEntry;
import ai.helper2.catalog.enrichment.EnrichmentCandidates.Part;
import ai.helper2.catalog.enrichment.EnrichmentCandidates.View;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.BadRequestException;
import ai.helper2.platform.NotFoundException;
import ai.helper2.platform.ProblemType;
import ai.helper2.scout.QueueOutcome;
import ai.helper2.scout.QueuePlace;
import ai.helper2.scout.usecase.PrioritiseEnrichment;
import ai.helper2.scout.usecase.RequeueEnrichment;
import ai.helper2.scout.usecase.SkipEnrichment;
import ai.helper2.shared.UtcTimes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/admin/enrichment-queue (ADMIN only, admin port): the queue page by page, and the admin's
 * actions on one resource (requeue, skip, prioritise; ADR 0045 §7).
 */
@RestController
@RequestMapping("/api/v1")
class EnrichmentQueueController implements EnrichmentQueueApi {

  private final ListEnrichmentQueue queue;
  private final RequeueEnrichment requeue;
  private final SkipEnrichment skip;
  private final PrioritiseEnrichment prioritise;

  EnrichmentQueueController(
      ListEnrichmentQueue queue,
      RequeueEnrichment requeue,
      SkipEnrichment skip,
      PrioritiseEnrichment prioritise) {
    this.queue = queue;
    this.requeue = requeue;
    this.skip = skip;
    this.prioritise = prioritise;
  }

  @Override
  public ResponseEntity<EnrichmentQueuePageDto> listEnrichmentQueue(
      @Nullable EnrichmentQueueViewDto view, @Nullable String after, Integer size) {
    ListEnrichmentQueue.Page page =
        queue.list(
            view == null ? View.WAITING : View.valueOf(view.getValue()),
            after == null ? null : decode(after),
            size);
    return ResponseEntity.ok(
        new EnrichmentQueuePageDto(
                page.items().stream().map(EnrichmentQueueController::entry).toList())
            .next(page.next() == null ? null : encode(page.next())));
  }

  @Override
  public ResponseEntity<Void> requeueEnrichment(UUID resourceId) {
    return answer(requeue.requeue(adminId(), resourceId));
  }

  @Override
  public ResponseEntity<Void> skipEnrichment(UUID resourceId, @Nullable EnrichmentSkipDto body) {
    return answer(skip.skip(adminId(), resourceId, body == null ? null : body.getReason()));
  }

  @Override
  public ResponseEntity<Void> prioritiseEnrichment(UUID resourceId) {
    return answer(prioritise.prioritise(adminId(), resourceId));
  }

  /** 204 when done, 404 for an unknown resource, 409 not-applicable with the reason in words. */
  private static ResponseEntity<Void> answer(QueueOutcome outcome) {
    return switch (outcome) {
      case DONE -> ResponseEntity.noContent().build();
      case NOT_FOUND -> throw new NotFoundException("Resource not found.");
      case NOT_STOPPED -> notApplicable("The resource is neither skipped nor out of retries.");
      case NOT_WAITING -> notApplicable("The resource does not wait in the enrichment queue.");
      case ALREADY_SKIPPED -> notApplicable("The resource is already skipped.");
      case ALREADY_PRIORITISED -> notApplicable("The resource is already at the top.");
      case TOP_FULL ->
          notApplicable(
              QueuePlace.MAX_PRIORITISED
                  + " resources already wait at the top. Try again after the next run.");
    };
  }

  private static ResponseEntity<Void> notApplicable(String detail) {
    throw new ApiProblem(ProblemType.NOT_APPLICABLE, detail);
  }

  /** The signed-in administrator's provider subject (GitHub: the numeric user id). */
  private static String adminId() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }

  private static EnrichmentQueueEntryDto entry(BacklogEntry e) {
    return new EnrichmentQueueEntryDto(
            e.id(),
            e.title(),
            EnrichmentQueueEntryDto.StateEnum.fromValue(e.state().name()),
            UtcTimes.odt(e.firstDiscoveredAt()),
            e.failedRuns())
        .waiting(
            e.waiting() == null
                ? null
                : EnrichmentQueueEntryDto.WaitingEnum.fromValue(e.waiting().name()))
        .skippedAt(e.skippedAt() == null ? null : UtcTimes.odt(e.skippedAt()))
        .skipReason(e.skipReason())
        .lastOutcome(
            e.lastOutcome() == null
                ? null
                : EnrichmentQueueEntryDto.LastOutcomeEnum.fromValue(e.lastOutcome()))
        .lastRunAt(e.lastRunAt() == null ? null : UtcTimes.odt(e.lastRunAt()));
  }

  /** The cursor is opaque to the page: the part, the time and the id, URL-safe. */
  static String encode(BacklogCursor cursor) {
    String plain = letter(cursor.part()) + "|" + cursor.at() + "|" + cursor.id();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
  }

  private static String letter(Part part) {
    return switch (part) {
      case PRIORITISED -> "P";
      case NEW -> "N";
      case REST -> "R";
    };
  }

  private static Part part(String letter) {
    return switch (letter) {
      case "P" -> Part.PRIORITISED;
      case "N" -> Part.NEW;
      case "R" -> Part.REST;
      default -> throw new IllegalArgumentException("part");
    };
  }

  /** A discovery time the database can hold; anything outside is no cursor of ours. */
  private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59Z");

  static BacklogCursor decode(String cursor) {
    try {
      String[] parts =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
              .split("\\|", -1);
      if (parts.length != 3) {
        throw new IllegalArgumentException("parts");
      }
      Instant at = Instant.parse(parts[1]);
      if (at.isBefore(Instant.EPOCH) || at.isAfter(LATEST)) {
        throw new IllegalArgumentException("time");
      }
      return new BacklogCursor(part(parts[0]), at, UUID.fromString(parts[2]));
    } catch (RuntimeException unreadable) {
      throw new BadRequestException("after is not a cursor from this queue");
    }
  }
}
