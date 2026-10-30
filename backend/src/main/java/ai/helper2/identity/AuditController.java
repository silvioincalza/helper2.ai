package ai.helper2.identity;

import ai.helper2.api.generated.AuditApi;
import ai.helper2.api.generated.model.AuditEntryDto;
import ai.helper2.api.generated.model.AuditEntryPageDto;
import ai.helper2.identity.internal.AuditLog;
import ai.helper2.identity.usecase.ListAuditEntries;
import java.time.ZoneOffset;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/v1/admin/audit (ADMIN only, SecurityConfiguration). */
@RestController
@RequestMapping("/api/v1")
class AuditController implements AuditApi {

    private final ListAuditEntries entries;

    AuditController(ListAuditEntries entries) {
        this.entries = entries;
    }

    @Override
    public ResponseEntity<AuditEntryPageDto> listAuditEntries(Integer page, Integer size) {
        AuditLog.Page found = entries.list(page, size);
        return ResponseEntity.ok(new AuditEntryPageDto(found.items().stream().map(AuditController::dto).toList(),
            Math.toIntExact(found.total())));
    }

    private static AuditEntryDto dto(AuditLog.Entry e) {
        return new AuditEntryDto(e.id(), e.adminId(), AuditEntryDto.ActionEnum.fromValue(e.action().name()),
            e.at().atOffset(ZoneOffset.UTC), e.outcome()).targetId(e.targetId());
    }
}
