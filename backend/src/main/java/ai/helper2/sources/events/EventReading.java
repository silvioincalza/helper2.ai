package ai.helper2.sources.events;

import ai.helper2.shared.EventFormat;
import ai.helper2.shared.EventKind;
import java.time.LocalDate;

/**
 * What a model read on one event page: how many events the page is about, the days with the quote
 * that states them, and kind, format and place, each with its own quote. Nothing of it is published
 * before {@link EventQuoteGate} checks it.
 */
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
