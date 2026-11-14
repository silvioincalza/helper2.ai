package ai.helper2.admin.web;

import ai.helper2.api.generated.CategoryBackfillApi;
import ai.helper2.catalog.usecase.BackfillCategories;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class CategoryBackfillController implements CategoryBackfillApi {

  private final BackfillCategories backfill;

  CategoryBackfillController(BackfillCategories backfill) {
    this.backfill = backfill;
  }

  @Override
  public ResponseEntity<Void> backfillCategories() {
    backfill.start(adminId());
    return ResponseEntity.status(HttpStatus.ACCEPTED).build();
  }

  private static String adminId() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }
}
