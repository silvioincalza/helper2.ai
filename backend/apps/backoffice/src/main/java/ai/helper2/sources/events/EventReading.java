package ai.helper2.sources.events;

import ai.helper2.shared.EventFormat;
import ai.helper2.shared.EventKind;
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
