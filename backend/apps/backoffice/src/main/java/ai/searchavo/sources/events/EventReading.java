package ai.searchavo.sources.events;

import ai.searchavo.shared.EventFormat;
import ai.searchavo.shared.EventKind;
import java.time.LocalDate;

public record EventReading(
    int eventsOnPage,
    LocalDate start,
    LocalDate end,
    String dateQuote,
    EventKind kind,
    String kindQuote,
    EventFormat format,
    String formatQuote,
    String location,
    String locationQuote) {}
