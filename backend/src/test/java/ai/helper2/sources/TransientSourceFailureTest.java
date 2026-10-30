package ai.helper2.sources;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.search.SourceFailure;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TransientSourceFailureTest {

    private final TransientSourceFailure retryable = new TransientSourceFailure();

    @Test
    void a503ATimeoutAndAShortRateLimitAreRetried() {
        assertThat(retryable.test(new SourceFailure.Unavailable("HTTP 503", true))).isTrue();
        assertThat(retryable.test(new SourceFailure.Unavailable("network error or timeout", true))).isTrue();
        assertThat(retryable.test(new SourceFailure.RateLimited(null))).isTrue();
        assertThat(retryable.test(new SourceFailure.RateLimited(Duration.ofSeconds(10)))).isTrue();
    }

    @Test
    void aPermanentFailureALongPauseOrAnotherErrorIsNot() {
        assertThat(retryable.test(new SourceFailure.Unavailable("HTTP 404"))).isFalse();
        assertThat(retryable.test(new SourceFailure.Unavailable("malformed response"))).isFalse();
        assertThat(retryable.test(new SourceFailure.RateLimited(Duration.ofMinutes(40)))).isFalse();
        assertThat(retryable.test(new IllegalStateException("bug"))).isFalse();
    }
}
