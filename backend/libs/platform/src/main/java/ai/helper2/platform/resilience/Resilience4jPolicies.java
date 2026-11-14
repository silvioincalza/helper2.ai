package ai.helper2.platform.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class Resilience4jPolicies implements Resilience {

  private final CircuitBreakerRegistry circuits;
  private final BulkheadRegistry bulkheads;
  private final RetryRegistry retries;
  private final Map<String, Instant> openedAt = new ConcurrentHashMap<>();

  public Resilience4jPolicies(
      CircuitBreakerRegistry circuits, BulkheadRegistry bulkheads, RetryRegistry retries) {
    this.circuits = circuits;
    this.bulkheads = bulkheads;
    this.retries = retries;
    circuits.getEventPublisher().onEntryAdded(added -> watch(added.getAddedEntry()));
    circuits.getAllCircuitBreakers().forEach(this::watch);
  }

  private void watch(CircuitBreaker circuit) {
    circuit
        .getEventPublisher()
        .onStateTransition(event -> {
          CircuitBreaker.State to = event.getStateTransition().getToState();
          if (to == CircuitBreaker.State.OPEN || to == CircuitBreaker.State.FORCED_OPEN) {
            openedAt.put(circuit.getName(), circuit.getCircuitBreakerConfig().getClock().instant());
          } else {
            openedAt.remove(circuit.getName());
          }
        });
  }

  @Override
  public <T> T call(String policy, Supplier<T> work) {
    Optional<CircuitBreaker> circuit = circuits.find(policy);
    Optional<Bulkhead> bulkhead = bulkheads.find(policy);
    Optional<Retry> retry = retries.find(policy);
    if (circuit.isEmpty() && bulkhead.isEmpty() && retry.isEmpty()) {
      throw unknown(policy);
    }
    Supplier<T> decorated = work;
    if (circuit.isPresent()) {
      decorated = CircuitBreaker.decorateSupplier(circuit.get(), decorated);
    }
    if (bulkhead.isPresent()) {
      decorated = Bulkhead.decorateSupplier(bulkhead.get(), decorated);
    }
    if (retry.isPresent()) {
      decorated = Retry.decorateSupplier(retry.get(), decorated);
    }
    try {
      return decorated.get();
    } catch (CallNotPermittedException e) {
      throw new CallRefused(policy, CallRefused.Reason.CIRCUIT_OPEN, e);
    } catch (BulkheadFullException e) {
      throw new CallRefused(policy, CallRefused.Reason.BUSY, e);
    }
  }

  @Override
  public Optional<Slot> trySlot(String policy) {
    Optional<Bulkhead> found = bulkheads.find(policy);
    if (found.isEmpty()) {
      return Resilience.super.trySlot(policy);
    }
    Bulkhead bulkhead = found.get();
    if (!bulkhead.tryAcquirePermission()) {
      return Optional.empty();
    }
    AtomicBoolean released = new AtomicBoolean();
    return Optional.of(() -> {
      if (released.compareAndSet(false, true)) {
        bulkhead.onComplete();
      }
    });
  }

  @Override
  public Optional<Circuit> circuit(String policy) {
    return circuits
        .find(policy)
        .map(circuit ->
            switch (circuit.getState()) {
              case OPEN, FORCED_OPEN -> new Circuit(Circuit.State.OPEN, openedAt.get(policy));
              case HALF_OPEN -> new Circuit(Circuit.State.HALF_OPEN, null);
              case CLOSED, DISABLED, METRICS_ONLY -> new Circuit(Circuit.State.CLOSED, null);
            });
  }

  @Override
  public void require(String policy) {
    if (circuits.find(policy).isEmpty()
        && bulkheads.find(policy).isEmpty()
        && retries.find(policy).isEmpty()) {
      throw unknown(policy);
    }
  }

  private static IllegalStateException unknown(String policy) {
    return new IllegalStateException(
        "no resilience policy named " + policy + " (resilience4j.* in the yaml settings)");
  }
}
