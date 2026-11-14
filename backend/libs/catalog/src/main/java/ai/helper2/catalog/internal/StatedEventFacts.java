package ai.helper2.catalog.internal;

import ai.helper2.shared.DateFact;
import ai.helper2.shared.EventFormat;
import ai.helper2.shared.EventKind;
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
