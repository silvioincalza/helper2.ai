package ai.helper2.platform.llm;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Ceiling on model calls per UTC day for one named use (guide, translation, enrichment, ...), across all
 * visitors. Each real provider call takes one unit, retries included: the per-client rate limits protect
 * against one noisy client, this protects the Token Factory credits that keep the public demo free.
 *
 * <p>Counted in a {@link CallCounter} (Redis) when there is one, so restarts do not reset the day. When the
 * counter fails, the count continues in memory from the last value it returned: an outage can only allow
 * more calls, never block the model by mistake.
 */
public final class DailyBudget {

    private final int dailyCalls;
    private final Clock clock;
    private final String name;
    private final CallCounter counter;
    private LocalDate day;
    private int used;

    public DailyBudget(int dailyCalls, Clock clock, String name, CallCounter counter) {
        this.dailyCalls = dailyCalls;
        this.clock = clock;
        this.name = name;
        this.counter = counter == null ? CallCounter.NONE : counter;
        this.day = today();
    }

    /** In memory only: for a budget that does not need to survive a restart. */
    public DailyBudget(int dailyCalls, Clock clock, String name) {
        this(dailyCalls, clock, name, CallCounter.NONE);
    }

    public String name() {
        return name;
    }

    /** Takes one call from today's budget. False when the budget is used up (or set to 0). */
    public boolean tryAcquire() {
        if (dailyCalls <= 0) {
            return false;
        }
        LocalDate now = today();
        // Outside the lock: a slow store must not queue every caller behind it.
        Long durable = durableCount(now);
        synchronized (this) {
            if (!now.equals(day)) {
                day = now;
                used = 0;
            }
            if (durable != null) {
                used = Math.max(used, (int) Math.min(durable, dailyCalls));
                return durable <= dailyCalls;
            }
            if (used >= dailyCalls) {
                return false;
            }
            used++;
            return true;
        }
    }

    public synchronized int usedToday() {
        return today().equals(day) ? used : 0;
    }

    /** Calls left today; for the budget gauges. */
    public int remaining() {
        return Math.max(0, dailyCalls - usedToday());
    }

    private Long durableCount(LocalDate now) {
        try {
            return counter.increment(name, now);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
