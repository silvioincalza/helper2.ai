package ai.helper2.sources.events;

import java.time.LocalDate;

/** The days an event takes place, first to last; a one-day event starts and ends the same day. */
public record DayRange(LocalDate start, LocalDate end) {}
