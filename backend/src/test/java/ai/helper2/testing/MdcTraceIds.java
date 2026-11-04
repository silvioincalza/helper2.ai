package ai.helper2.testing;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Stands in for the tracing bridge in unit tests: an observation gets a trace id, which is in the
 * MDC while the observation is in scope, as Micrometer Tracing does for {@code traceId}. Tests that
 * work with the real bridge and the logstash format run in {@code StructuredLogTraceIdIT}.
 */
public final class MdcTraceIds implements ObservationHandler<Observation.Context> {

  public static final String KEY = "traceId";

  public static ObservationRegistry registry() {
    ObservationRegistry registry = ObservationRegistry.create();
    registry.observationConfig().observationHandler(new MdcTraceIds());
    return registry;
  }

  /** The trace id the MDC holds while {@code observation} is in scope. */
  public static String traceId(Observation observation) {
    return observation.getContext().getRequired(KEY);
  }

  @Override
  public void onStart(Observation.Context context) {
    context.put(KEY, UUID.randomUUID().toString().replace("-", ""));
  }

  @Override
  public void onScopeOpened(Observation.Context context) {
    MDC.put(KEY, context.getRequired(KEY));
  }

  @Override
  public void onScopeClosed(Observation.Context context) {
    MDC.remove(KEY);
  }

  @Override
  public boolean supportsContext(Observation.Context context) {
    return true;
  }
}
