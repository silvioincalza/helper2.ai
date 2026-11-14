package ai.helper2.platform.quota;

public interface QuotaCounter {
  record Counter(String key, String field) {}

  enum Verdict {
    TAKEN,
    LIMIT_REACHED,
    GLOBAL_LIMIT_REACHED,
  }

  record Result(Verdict verdict, long used) {}

  Result take(Counter own, int ownLimit, Counter global, long globalLimit);

  void add(Counter counter, long amount);

  void giveBack(Counter counter);

  long used(Counter counter);
}
