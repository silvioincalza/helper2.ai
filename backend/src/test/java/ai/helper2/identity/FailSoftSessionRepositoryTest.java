package ai.helper2.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.identity.SessionConfiguration.FailSoftSessionRepository;
import ai.helper2.platform.resilience.CallRefused;
import ai.helper2.platform.resilience.Resilience;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;

class FailSoftSessionRepositoryTest {

    private final FailingStore store = new FailingStore();

    @Test
    void readingASessionWhileTheStoreFailsFindsNone() {
        store.failing = true;
        var sessions = new FailSoftSessionRepository<>(store, Resilience.NONE);

        assertThat(sessions.findById("abc")).isNull();
    }

    @Test
    void readingASessionWhileTheRedisCircuitIsOpenSkipsTheStore() {
        var policies = new RecordingResilience();
        policies.refuse = true;
        var sessions = new FailSoftSessionRepository<>(store, policies);

        assertThat(sessions.findById("abc")).isNull();
        assertThat(store.reads).isZero();
        assertThat(policies.policies).containsExactly("redis");
    }

    @Test
    void readingASessionGoesThroughTheRedisPolicy() {
        var policies = new RecordingResilience();
        var sessions = new FailSoftSessionRepository<>(store, policies);

        assertThat(sessions.findById("abc")).isNotNull();
        assertThat(policies.policies).containsExactly("redis");
    }

    @Test
    void savingASessionWhileTheStoreFailsStillFails() {
        store.failing = true;
        var sessions = new FailSoftSessionRepository<>(store, Resilience.NONE);

        assertThatThrownBy(() -> sessions.save(new MapSession("abc"))).isInstanceOf(IllegalStateException.class);
    }

    private static final class FailingStore implements SessionRepository<MapSession> {

        boolean failing;
        int reads;

        @Override
        public MapSession createSession() {
            return new MapSession();
        }

        @Override
        public void save(MapSession session) {
            if (failing) {
                throw new IllegalStateException("store down");
            }
        }

        @Override
        public MapSession findById(String id) {
            reads++;
            if (failing) {
                throw new IllegalStateException("store down");
            }
            return new MapSession(id);
        }

        @Override
        public void deleteById(String id) {
        }
    }

    private static final class RecordingResilience implements Resilience {

        final List<String> policies = new ArrayList<>();
        boolean refuse;

        @Override
        public <T> T call(String policy, Supplier<T> work) {
            policies.add(policy);
            if (refuse) {
                throw new CallRefused(policy, CallRefused.Reason.CIRCUIT_OPEN, null);
            }
            return work.get();
        }

        @Override
        public void require(String policy) {
        }
    }
}
