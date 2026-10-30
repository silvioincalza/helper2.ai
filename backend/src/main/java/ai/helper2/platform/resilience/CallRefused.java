package ai.helper2.platform.resilience;

/** A call a policy did not let through: the work did not run. */
public final class CallRefused extends RuntimeException {

    public enum Reason {
        /** The circuit breaker is open after too many failed or slow calls. */
        CIRCUIT_OPEN,
        /** The bulkhead already runs as many calls as it allows. */
        BUSY,
    }

    private final String policy;
    private final Reason reason;

    public CallRefused(String policy, Reason reason, Throwable cause) {
        super(policy + ": " + reason, cause, false, false);
        this.policy = policy;
        this.reason = reason;
    }

    public String policy() {
        return policy;
    }

    public Reason reason() {
        return reason;
    }
}
