package ai.helper2.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface AiCheckReport {

  record Cursor(BigDecimal popularity, UUID id) {}

  record Row(
      UUID resourceId,
      String title,
      @Nullable String description,
      List<String> tags,
      String type,
      BigDecimal popularity) {}

  List<Row> mostPopular(@Nullable Cursor after, int size);
}
