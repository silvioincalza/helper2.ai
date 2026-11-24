package ai.searchavo.admin.web;

import ai.searchavo.api.generated.CategoryBackfillApi;
import ai.searchavo.catalog.usecase.BackfillCategories;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    backfill.start(Admins.id());
    return ResponseEntity.status(HttpStatus.ACCEPTED).build();
  }
}
