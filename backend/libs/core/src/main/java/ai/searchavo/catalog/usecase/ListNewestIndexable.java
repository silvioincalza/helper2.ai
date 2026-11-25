package ai.searchavo.catalog.usecase;

import ai.searchavo.catalog.ResourceCards;
import ai.searchavo.catalog.Resources;
import ai.searchavo.search.MergedResult;
import java.util.List;
import java.util.UUID;

public class ListNewestIndexable {

  private final Resources store;
  private final ResourceCards cards;

  public ListNewestIndexable(Resources store, ResourceCards cards) {
    this.store = store;
    this.cards = cards;
  }

  public List<MergedResult> list(String type, int promptMinLength, int limit) {
    List<UUID> ids =
        store.indexable(List.of(type), promptMinLength, limit).stream()
            .map(Resources.Indexable::id)
            .toList();
    return cards.cardsOf(ids);
  }
}
