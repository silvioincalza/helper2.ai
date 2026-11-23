package ai.helper2.catalog.usecase;

import ai.helper2.catalog.ResourceCards;
import ai.helper2.catalog.Resources;
import ai.helper2.search.MergedResult;
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
