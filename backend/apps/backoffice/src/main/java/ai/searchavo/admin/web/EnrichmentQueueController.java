package ai.searchavo.admin.web;

import ai.searchavo.admin.usecase.CheckAiWords;
import ai.searchavo.admin.usecase.CheckAwesomeLists;
import ai.searchavo.admin.usecase.ListEnrichmentQueue;
import ai.searchavo.api.generated.EnrichmentQueueApi;
import ai.searchavo.api.generated.model.AiCheckPageDto;
import ai.searchavo.api.generated.model.AiCheckRowDto;
import ai.searchavo.api.generated.model.AwesomeListCheckDto;
import ai.searchavo.api.generated.model.EnrichmentQueueEntryDto;
import ai.searchavo.api.generated.model.EnrichmentQueuePageDto;
import ai.searchavo.api.generated.model.EnrichmentQueueViewDto;
import ai.searchavo.api.generated.model.EnrichmentSkipDto;
import ai.searchavo.api.generated.model.ResourceRemovalDto;
import ai.searchavo.catalog.AiCheckReport;
import ai.searchavo.catalog.enrichment.EnrichmentCandidates.BacklogCursor;
import ai.searchavo.catalog.enrichment.EnrichmentCandidates.Part;
import ai.searchavo.catalog.usecase.RemoveResource;
import ai.searchavo.platform.ApiProblem;
import ai.searchavo.platform.BadRequestException;
import ai.searchavo.platform.NotFoundException;
import ai.searchavo.platform.ProblemType;
import ai.searchavo.scout.QueueOutcome;
import ai.searchavo.scout.QueuePlace;
import ai.searchavo.scout.usecase.PrioritiseEnrichment;
import ai.searchavo.scout.usecase.RequeueEnrichment;
import ai.searchavo.scout.usecase.SkipEnrichment;
import ai.searchavo.shared.UtcTimes;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class EnrichmentQueueController implements EnrichmentQueueApi {

  private final ListEnrichmentQueue queue;
  private final RequeueEnrichment requeue;
  private final SkipEnrichment skip;
  private final PrioritiseEnrichment prioritise;
  private final CheckAiWords aiCheck;
  private final CheckAwesomeLists awesomeLists;
  private final RemoveResource remove;

  EnrichmentQueueController(
      ListEnrichmentQueue queue,
      RequeueEnrichment requeue,
      SkipEnrichment skip,
      PrioritiseEnrichment prioritise,
      CheckAiWords aiCheck,
      CheckAwesomeLists awesomeLists,
      RemoveResource remove) {
    this.remove = remove;
    this.aiCheck = aiCheck;
    this.awesomeLists = awesomeLists;
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
            view == null
                ? ListEnrichmentQueue.Section.WAITING
                : ListEnrichmentQueue.Section.valueOf(view.getValue()),
            after == null ? null : decode(after),
            size);
    return ResponseEntity.ok(
        new EnrichmentQueuePageDto(
                page.items().stream().map(EnrichmentQueueController::entry).toList())
            .next(page.next() == null ? null : encode(page.next())));
  }

  @Override
  public ResponseEntity<AwesomeListCheckDto> checkAwesomeLists() {
    CheckAwesomeLists.Result r = awesomeLists.run(Admins.id());
    return ResponseEntity.ok(
        new AwesomeListCheckDto(
            r.checked(), r.set(), r.notAList(), r.keptCurated(), r.unavailable()));
  }

  @Override
  public ResponseEntity<AiCheckPageDto> checkAiWords(@Nullable String after, Integer size) {
    CheckAiWords.Page page = aiCheck.next(after == null ? null : aiCursor(after), size);
    return ResponseEntity.ok(
        new AiCheckPageDto(
                page.notAboutAi().stream()
                    .map(f ->
                        new AiCheckRowDto(
                            f.row().resourceId(),
                            f.row().title(),
                            f.row().type(),
                            f.row().popularity(),
                            f.waiting()))
                    .toList(),
                page.checked())
            .next(page.next() == null ? null : aiCursor(page.next())));
  }

  static String aiCursor(AiCheckReport.Cursor cursor) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            (cursor.popularity().toPlainString() + "|" + cursor.id())
                .getBytes(StandardCharsets.UTF_8));
  }

  static AiCheckReport.Cursor aiCursor(String cursor) {
    try {
      String[] parts =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
              .split("\\|", -1);
      BigDecimal popularity = new BigDecimal(parts[0]);
      if (parts.length != 2
          || popularity.signum() < 0
          || popularity.compareTo(BigDecimal.ONE) > 0) {
        throw new IllegalArgumentException("cursor");
      }
      return new AiCheckReport.Cursor(popularity, UUID.fromString(parts[1]));
    } catch (RuntimeException unreadable) {
      throw new BadRequestException("after is not a cursor from this check");
    }
  }

  @Override
  public ResponseEntity<Void> requeueEnrichment(UUID resourceId) {
    return answer(requeue.requeue(Admins.id(), resourceId));
  }

  @Override
  public ResponseEntity<Void> skipEnrichment(UUID resourceId, @Nullable EnrichmentSkipDto body) {
    return answer(skip.skip(Admins.id(), resourceId, body == null ? null : body.getReason()));
  }

  @Override
  public ResponseEntity<Void> removeResourceNotAboutAi(UUID resourceId, ResourceRemovalDto body) {
    return switch (remove.remove(resourceId, body.getReason(), Admins.id())) {
      case RemoveResource.Outcome.Removed removed -> ResponseEntity.noContent().build();
      case RemoveResource.Outcome.NoReason noReason ->
          throw new BadRequestException(
              "A reason of 1 to " + noReason.maxLength() + " characters is required.");
      case RemoveResource.Outcome.NoSuchResource missing ->
          throw new NotFoundException("Resource not found.");
    };
  }

  @Override
  public ResponseEntity<Void> prioritiseEnrichment(UUID resourceId) {
    return answer(prioritise.prioritise(Admins.id(), resourceId));
  }

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

  private static EnrichmentQueueEntryDto entry(ListEnrichmentQueue.Entry e) {
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

  static String encode(BacklogCursor cursor) {
    String plain =
        String.join(
            "|",
            letter(cursor.part()),
            cursor.popularity().toPlainString(),
            cursor.at().toString(),
            cursor.id().toString());
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

  private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59Z");

  static BacklogCursor decode(String cursor) {
    try {
      String[] parts =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
              .split("\\|", -1);
      if (parts.length != 4) {
        throw new IllegalArgumentException("parts");
      }
      BigDecimal popularity = new BigDecimal(parts[1]);
      if (popularity.signum() < 0 || popularity.compareTo(BigDecimal.ONE) > 0) {
        throw new IllegalArgumentException("popularity");
      }
      Instant at = Instant.parse(parts[2]);
      if (at.isBefore(Instant.EPOCH) || at.isAfter(LATEST)) {
        throw new IllegalArgumentException("time");
      }
      return new BacklogCursor(part(parts[0]), popularity, at, UUID.fromString(parts[3]));
    } catch (RuntimeException unreadable) {
      throw new BadRequestException("after is not a cursor from this queue");
    }
  }
}
