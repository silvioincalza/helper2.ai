package ai.helper2.testing;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.UUID;
import org.slf4j.MDC;

public final class MdcTraceIds implements ObservationHandler<Observation.Context> {

  public static final String KEY = "traceId";

  public static ObservationRegistry registry() {
    ObservationRegistry registry = ObservationRegistry.create();
    registry.observationConfig().observationHandler(new MdcTraceIds());
    return registry;
  }

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
