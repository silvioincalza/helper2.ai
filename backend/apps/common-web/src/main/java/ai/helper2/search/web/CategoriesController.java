package ai.helper2.search.web;

import ai.helper2.api.generated.CategoriesApi;
import ai.helper2.api.generated.model.CategoryDto;
import ai.helper2.api.generated.model.SourceFamilyDto;
import ai.helper2.shared.CategoryCatalog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The categories search can filter by (ADR 0013), in the configured order. They change only with a
 * restart, so the list is built once, cached by the browser for an hour and validated by its ETag
 * (Spring answers 304 when If-None-Match matches).
 */
@RestController
@RequestMapping("/api/v1")
class CategoriesController implements CategoriesApi {

  private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofHours(1)).cachePublic();

  private final List<CategoryDto> categories;
  private final String etag;

  CategoriesController(CategoryCatalog catalog) {
    this.categories =
        catalog.getAll().stream()
            .map(c ->
                new CategoryDto(
                    c.getId(),
                    c.getName(),
                    c.getShortName(),
                    SourceFamilyDto.fromValue(c.getFamily())))
            .toList();
    this.etag = etagOf(categories);
  }

  @Override
  public ResponseEntity<List<CategoryDto>> listCategories() {
    return ResponseEntity.ok().cacheControl(CACHE).eTag(etag).body(categories);
  }

  private static String etagOf(List<CategoryDto> categories) {
    StringBuilder text = new StringBuilder();
    categories.forEach(c ->
        text.append(c.getId())
            .append('|')
            .append(c.getName())
            .append('|')
            .append(c.getShortName())
            .append('|')
            .append(c.getFamily().getValue())
            .append('\n'));
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest(text.toString().getBytes(StandardCharsets.UTF_8));
      return '"' + HexFormat.of().formatHex(hash, 0, 8) + '"';
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
