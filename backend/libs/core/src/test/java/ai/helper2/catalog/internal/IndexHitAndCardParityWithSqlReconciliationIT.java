package ai.helper2.catalog.internal;

import java.util.List;
import java.util.stream.Stream;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "helper2.catalog.reconcile-by-projection=false")
class IndexHitAndCardParityWithSqlReconciliationIT extends IndexHitAndCardParityIT {

  private static final List<String> THE_SQL_RULE_KEEPS_THE_LETTER_CASE_OF_THE_STORED_LICENCE =
      List.of(
          "one licence, two letter cases, reversed:"
              + " licence is CC-BY-4.0 in the index, cc-by-4.0 on the card");

  @Override
  List<String> knownGaps() {
    return Stream.of(
            THE_ROW_KEEPS_THE_TITLE_OF_THE_LAST_WRITE,
            THE_SQL_RULE_KEEPS_THE_LETTER_CASE_OF_THE_STORED_LICENCE)
        .flatMap(List::stream)
        .toList();
  }
}
