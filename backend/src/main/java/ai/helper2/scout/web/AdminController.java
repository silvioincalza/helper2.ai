package ai.helper2.scout.web;

import ai.helper2.api.generated.AdminApi;
import ai.helper2.api.generated.model.ReviewItemDto;
import ai.helper2.api.generated.model.ReviewResolutionDto;
import ai.helper2.api.generated.model.ReviewStatusDto;
import ai.helper2.api.generated.model.ScoutRunDto;
import ai.helper2.api.generated.model.ScoutRunRequestDto;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.usecase.ListReviewItems;
import ai.helper2.catalog.usecase.ResolveReviewItem;
import ai.helper2.platform.NotFoundException;
import ai.helper2.scout.RunSummary;
import ai.helper2.scout.usecase.RunScout;
import ai.helper2.scout.ScoutStore;
import ai.helper2.search.web.CardMapper;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class AdminController implements AdminApi {

    private final ListReviewItems items;
    private final ResolveReviewItem resolve;
    private final RunScout runner;
    private final ScoutStore store;

    AdminController(ListReviewItems items, ResolveReviewItem resolve, RunScout runner, ScoutStore store) {
        this.items = items;
        this.resolve = resolve;
        this.runner = runner;
        this.store = store;
    }

    @Override
    public ResponseEntity<List<ReviewItemDto>> listReviewItems(@Nullable ReviewStatusDto status) {
        return ResponseEntity.ok(items.list(status == null ? null : ReviewQueue.Status.valueOf(status.getValue()))
            .stream().map(AdminController::dto).toList());
    }

    @Override
    public ResponseEntity<ReviewItemDto> resolveReviewItem(UUID id, ReviewResolutionDto body) {
        String by = SecurityContextHolder.getContext().getAuthentication().getName();
        return resolve.resolve(id, ReviewQueue.Status.valueOf(body.getStatus().getValue()), by, body.getNote())
            .map(i -> ResponseEntity.ok(dto(i)))
            .orElseThrow(() -> new NotFoundException("Review item not found."));
    }

    @Override
    public ResponseEntity<List<ScoutRunDto>> listScoutRuns() {
        return ResponseEntity.ok(store.recent(50).stream().map(AdminController::dto).toList());
    }

    @Override
    public ResponseEntity<List<ScoutRunDto>> startScoutRun(@Nullable ScoutRunRequestDto body) {
        List<RunSummary> runs = runner.tryRunAll(body == null ? null : body.getSourceId());
        if (runs == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A Scout run is already in progress. Try again when it ends.");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(runs.stream().map(AdminController::dto).toList());
    }

    private static ReviewItemDto dto(ReviewQueue.Item i) {
        return new ReviewItemDto(i.id(), ReviewItemDto.KindEnum.fromValue(i.kind().name()), ReviewStatusDto.fromValue(i.status().name()),
            i.reason(), CardMapper.odt(i.createdAt()))
            .resourceId(i.resourceId()).resourceTitle(i.resourceTitle()).details(i.details())
            .resolvedAt(CardMapper.odt(i.resolvedAt())).resolvedBy(i.resolvedBy());
    }

    private static ScoutRunDto dto(RunSummary r) {
        return new ScoutRunDto(r.getId(), r.getSourceId(), ScoutRunDto.StatusEnum.fromValue(r.getStatus().name()),
            CardMapper.odt(r.getStartedAt()), r.getItemsSeen(), r.getItemsChanged())
            .finishedAt(CardMapper.odt(r.getFinishedAt())).enrichmentsQueued(r.getEnrichmentsQueued()).message(r.getMessage());
    }
}
