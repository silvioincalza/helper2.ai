package ai.searchavo.catalog.internal;

import ai.searchavo.shared.DateFact;
import ai.searchavo.shared.EventFormat;
import ai.searchavo.shared.EventKind;
import org.jspecify.annotations.Nullable;

record StatedEventFacts(
    DateFact starts,
    DateFact ends,
    @Nullable String location,
    @Nullable EventKind kind,
    @Nullable EventFormat format) {

  static final StatedEventFacts UNKNOWN =
      new StatedEventFacts(DateFact.unknown(), DateFact.unknown(), null, null, null);
}
