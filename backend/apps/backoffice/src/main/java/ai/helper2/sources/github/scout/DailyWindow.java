package ai.helper2.sources.github.scout;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

final class DailyWindow {

  private DailyWindow() {}

  static <T> List<T> of(List<T> items, int size, Instant now) {
    if (size <= 0 || items.isEmpty()) {
      return List.of();
    }
    if (items.size() <= size) {
      return items;
    }
    long day = LocalDate.ofInstant(now, ZoneOffset.UTC).toEpochDay();
    int start = (int) Math.floorMod(day * size, (long) items.size());
    List<T> window = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      window.add(items.get((start + i) % items.size()));
    }
    return window;
  }
}
