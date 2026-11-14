package ai.helper2.platform.llm;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.function.IntSupplier;

public final class DailyBudget {

  private final IntSupplier dailyCalls;
  private final Clock clock;
  private final String name;
  private final CallCounter counter;
  private final int paceSlots;
  private LocalDate day;
  private int used;

  private final Set<String> givenBack = new HashSet<>();

  public DailyBudget(IntSupplier dailyCalls, Clock clock, String name, CallCounter counter) {
    this(dailyCalls, clock, name, counter, 1);
  }

  private DailyBudget(
      IntSupplier dailyCalls, Clock clock, String name, CallCounter counter, int paceSlots) {
    if (paceSlots < 1 || paceSlots > HOURS_A_DAY) {
      throw new IllegalArgumentException("pace slots must be 1 to 24, got " + paceSlots);
    }
    this.dailyCalls = dailyCalls;
    this.clock = clock;
    this.name = name;
    this.counter = counter == null ? CallCounter.NONE : counter;
    this.paceSlots = paceSlots;
    this.day = today();
    this.counter.declare(name, dailyCalls);
  }

  public static DailyBudget paced(
      IntSupplier dailyCalls, Clock clock, String name, CallCounter counter, int paceSlots) {
    return new DailyBudget(dailyCalls, clock, name, counter, paceSlots);
  }

  private static final int HOURS_A_DAY = 24;

  public DailyBudget(int dailyCalls, Clock clock, String name, CallCounter counter) {
    this(() -> dailyCalls, clock, name, counter);
  }

  public DailyBudget(int dailyCalls, Clock clock, String name) {
    this(dailyCalls, clock, name, CallCounter.NONE);
  }

  public String name() {
    return name;
  }

  public boolean tryAcquire() {
    int limit = limit();
    if (limit <= 0) {
      return false;
    }
    int allowed = allowedSoFarToday(limit);
    LocalDate now = today();
    Long durable = durableCount(now, allowed);
    synchronized (this) {
      startDay(now);
      if (durable != null) {
        used = Math.max(used, (int) Math.min(durable, allowed));
        return durable <= allowed;
      }
      if (used >= allowed) {
        return false;
      }
      used++;
      return true;
    }
  }

  public CallCounter.GiveBack giveBack(LocalDate taken, String once, int dailyCap) {
    CallCounter.GiveBack durable = durableGiveBack(taken, once, dailyCap);
    synchronized (this) {
      startDay(today());
      if (!taken.equals(day)) {
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

  public boolean undo(LocalDate taken) {
    Boolean durable = durableUndo(taken);
    synchronized (this) {
      startDay(today());
      if (!taken.equals(day)) {
        return Boolean.TRUE.equals(durable);
      }
      if (Boolean.FALSE.equals(durable) || (durable == null && used <= 0)) {
        return false;
      }
      used = Math.max(0, used - 1);
      return true;
    }
  }

  private Boolean durableUndo(LocalDate taken) {
    try {
      return counter.undo(name, taken);
    } catch (RuntimeException e) {
      return null;
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

  public int remainingSoFarToday() {
    return Math.max(0, allowedSoFarToday(limit()) - usedToday());
  }

  public int remaining() {
    return Math.max(0, limit() - usedToday());
  }

  public boolean opensMoreToday() {
    return remaining() > 0;
  }

  public Instant opensAgainAt() {
    ZonedDateTime now = clock.instant().atZone(ZoneOffset.UTC);
    ZonedDateTime startOfDay = now.truncatedTo(ChronoUnit.DAYS);
    int nextSlot = now.getHour() * paceSlots / HOURS_A_DAY + 1;
    if (nextSlot >= paceSlots || !opensMoreToday()) {
      return startOfDay.plusDays(1).toInstant();
    }
    int firstHourOfNextSlot = (nextSlot * HOURS_A_DAY + paceSlots - 1) / paceSlots;
    return startOfDay.plusHours(firstHourOfNextSlot).toInstant();
  }

  public int limit() {
    return dailyCalls.getAsInt();
  }

  int allowedSoFarToday(int limit) {
    if (paceSlots == 1) {
      return limit;
    }
    int hour = clock.instant().atZone(ZoneOffset.UTC).getHour();
    int slot = hour * paceSlots / HOURS_A_DAY;
    return (int) Math.ceil((double) limit * (slot + 1) / paceSlots);
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
