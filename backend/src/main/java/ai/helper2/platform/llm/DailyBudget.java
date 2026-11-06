package ai.helper2.platform.llm;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * Ceiling on model calls per UTC day for one named use (guide, translation, enrichment, ...),
 * across all visitors. Each real provider call takes one unit, retries included: the per-client
 * rate limits protect against one noisy client, this protects the Token Factory credits that keep
 * the public demo free.
 *
 * <p>Counted in a {@link CallCounter} (Redis) when there is one, so restarts do not reset the day.
 * When the counter fails, the count continues in memory from the last value it returned: an outage
 * can only allow more calls, never block the model by mistake.
 *
 * <p>The limit is read through a supplier on every call, so a runtime setting (ADR 0060) applies at
 * once, without a restart. A limit lowered below today's count refuses the next calls.
 */
public final class DailyBudget {

  private final IntSupplier dailyCalls;
  private final Clock clock;
  private final String name;
  private final CallCounter counter;
  private LocalDate day;
  private int used;

  /** The {@code once} ids given back today, in memory; the durable store keeps its own. */
  private final Set<String> givenBack = new HashSet<>();

  public DailyBudget(IntSupplier dailyCalls, Clock clock, String name, CallCounter counter) {
    this.dailyCalls = dailyCalls;
    this.clock = clock;
    this.name = name;
    this.counter = counter == null ? CallCounter.NONE : counter;
    this.day = today();
    this.counter.declare(name, dailyCalls);
  }

  /** A fixed limit. */
  public DailyBudget(int dailyCalls, Clock clock, String name, CallCounter counter) {
    this(() -> dailyCalls, clock, name, counter);
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
    int limit = limit();
    if (limit <= 0) {
      return false;
    }
    LocalDate now = today();
    // Outside the lock: a slow store must not queue every caller behind it.
    Long durable = durableCount(now, limit);
    synchronized (this) {
      startDay(now);
      if (durable != null) {
        used = Math.max(used, (int) Math.min(durable, limit));
        return durable <= limit;
      }
      if (used >= limit) {
        return false;
      }
      used++;
      return true;
    }
  }

  /**
   * Gives back one call taken on [taken] whose work turned out free: at most once per [once] and at
   * most [dailyCap] times a day, never below 0 ({@link CallCounter#giveBack}). In the durable store
   * when it answers, else in memory, like {@link #tryAcquire}.
   */
  public CallCounter.GiveBack giveBack(LocalDate taken, String once, int dailyCap) {
    // Outside the lock, as in tryAcquire.
    CallCounter.GiveBack durable = durableGiveBack(taken, once, dailyCap);
    synchronized (this) {
      startDay(today());
      if (!taken.equals(day)) {
        // The day of the call is over: its count is gone, here and in every quota.
        return durable == null ? CallCounter.GiveBack.NOTHING_COUNTED : durable;
      }
      CallCounter.GiveBack outcome = durable == null ? giveBackInMemory(once, dailyCap) : durable;
      if (durable == CallCounter.GiveBack.GIVEN_BACK) {
        used = Math.max(0, used - 1);
        givenBack.add(once);
      }
      return outcome;
    }
  }

  private CallCounter.GiveBack giveBackInMemory(String once, int dailyCap) {
    if (givenBack.contains(once)) {
      return CallCounter.GiveBack.ALREADY_GIVEN_BACK;
    }
    if (givenBack.size() >= dailyCap) {
      return CallCounter.GiveBack.CAP_REACHED;
    }
    if (used <= 0) {
      return CallCounter.GiveBack.NOTHING_COUNTED;
    }
    used--;
    givenBack.add(once);
    return CallCounter.GiveBack.GIVEN_BACK;
  }

  private void startDay(LocalDate now) {
    if (!now.equals(day)) {
      day = now;
      used = 0;
      givenBack.clear();
    }
  }

  private CallCounter.GiveBack durableGiveBack(LocalDate taken, String once, int dailyCap) {
    try {
      return counter.giveBack(name, taken, once, dailyCap);
    } catch (RuntimeException e) {
      return null;
    }
  }

  public synchronized int usedToday() {
    return today().equals(day) ? used : 0;
  }

  /** Calls left today; for the budget gauges. */
  public int remaining() {
    return Math.max(0, limit() - usedToday());
  }

  /** Today's limit as it is now; 0 or less turns the use off. */
  public int limit() {
    return dailyCalls.getAsInt();
  }

  private Long durableCount(LocalDate now, int limit) {
    try {
      return counter.increment(name, now, limit);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
