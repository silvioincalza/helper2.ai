package ai.helper2.platform.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link Resilience} on the Resilience4j registries, decorating each call in code (no annotations, no proxies). A
 * policy is the circuit breaker, bulkhead and retry instances that share its name; it has at least one of them.
 * Order, outermost first: retry, bulkhead, circuit breaker. A busy bulkhead is not a failure of the dependency, so
 * it never reaches the circuit; a refused call is never retried (the retry predicates only match failures of the work).
 */
public final class Resilience4jPolicies implements Resilience {

    private final CircuitBreakerRegistry circuits;
    private final BulkheadRegistry bulkheads;
    private final RetryRegistry retries;

    public Resilience4jPolicies(CircuitBreakerRegistry circuits, BulkheadRegistry bulkheads, RetryRegistry retries) {
        this.circuits = circuits;
        this.bulkheads = bulkheads;
        this.retries = retries;
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
    public void require(String policy) {
        if (circuits.find(policy).isEmpty() && bulkheads.find(policy).isEmpty() && retries.find(policy).isEmpty()) {
            throw unknown(policy);
        }
    }

    private static IllegalStateException unknown(String policy) {
        return new IllegalStateException("no resilience policy named " + policy + " (resilience4j.* in application.yaml)");
    }
}
