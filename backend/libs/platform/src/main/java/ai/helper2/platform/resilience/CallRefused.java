package ai.helper2.platform.resilience;

public final class CallRefused extends RuntimeException {

  public enum Reason {
    CIRCUIT_OPEN,
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
