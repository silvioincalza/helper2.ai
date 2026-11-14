package ai.helper2.platform;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;

public class InMemoryRateLimiter implements RateLimiter {

  static final int DEFAULT_MAX_CLIENTS = 50_000;

  private static final class Window {
    final Instant start;
    int count = 1;

    Window(Instant start) {
      this.start = start;
    }
  }

  private final int limit;
  private final Duration window;
  private final Clock clock;
  private final int maxClients;
  private final LinkedHashMap<String, Window> windows = new LinkedHashMap<>();

  public InMemoryRateLimiter(int limit, Duration window, Clock clock) {
    this(limit, window, clock, DEFAULT_MAX_CLIENTS);
  }

  InMemoryRateLimiter(int limit, Duration window, Clock clock, int maxClients) {
    this.limit = limit;
    this.window = window;
    this.clock = clock;
    this.maxClients = maxClients;
  }

  @Override
  public synchronized long acquire(String clientAddress) {
    Instant now = clock.instant();
    dropExpired(now);
    String key = ClientKey.of(clientAddress);
    Window w = windows.get(key);
    if (w == null || expired(w, now)) {
      windows.remove(key);
      w = new Window(now);
      windows.put(key, w);
      if (windows.size() > maxClients) {
        Iterator<Window> eldest = windows.values().iterator();
        eldest.next();
        eldest.remove();
      }
    } else {
      w.count++;
    }
    return w.count <= limit
        ? 0
        : Math.max(1, Duration.between(now, w.start.plus(window)).toSeconds());
  }

  synchronized int trackedClients() {
    return windows.size();
  }

  private void dropExpired(Instant now) {
    Iterator<Window> it = windows.values().iterator();
    while (it.hasNext() && expired(it.next(), now)) {
      it.remove();
    }
  }

  private boolean expired(Window w, Instant now) {
    return !now.isBefore(w.start.plus(window));
  }
}
