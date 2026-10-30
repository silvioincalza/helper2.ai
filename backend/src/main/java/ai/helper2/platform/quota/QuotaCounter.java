package ai.helper2.platform.quota;

import java.util.Collection;

/**
 * Daily counters every replica shares (ADR 0037): a count is one field of a key, and a key expires one day after its
 * first count. {@link QuotaKeys} names the keys. Every method throws when the store cannot answer; the caller decides
 * what that means.
 */
public interface QuotaCounter {

    /** One count: a key and one of its fields (a feature, a source). */
    record Counter(String key, String field) {
    }

    enum Verdict {
        /** Counted on every counter given. */
        TAKEN,
        /** The own counter is at its limit; nothing was counted. */
        LIMIT_REACHED,
        /** The global counter is at its limit; nothing was counted. */
        GLOBAL_LIMIT_REACHED,
    }

    /** {@code used}: the own counter after the call (unchanged when refused); 0 without an own counter. */
    record Result(Verdict verdict, long used) {
    }

    /**
     * In one atomic step: refuses when {@code own} (if not null) has reached {@code ownLimit} or {@code global} (if not
     * null) has reached {@code globalLimit}; otherwise adds one to each. Concurrent callers never pass a limit.
     */
    Result take(Counter own, int ownLimit, Counter global, long globalLimit);

    /** The count of {@code counter}; 0 when nothing was counted. */
    long used(Counter counter);

    void delete(Collection<String> keys);
}
