package ai.helper2.platform.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.testing.MovableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QuotaKeysTest {

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-30T23:59:00Z"));
    private final QuotaKeys keys = new QuotaKeys("test-secret", clock);

    @Test
    void aGuestKeyHoldsNoAddressAndChangesWithTheUtcDay() {
        String today = keys.guest("203.0.113.7");

        assertThat(today).startsWith("h2:v1:quota:guest:").doesNotContain("203.0.113.7").hasSize("h2:v1:quota:guest:".length() + 64);
        assertThat(keys.guest("203.0.113.7")).as("same client, same day").isEqualTo(today);
        assertThat(keys.guest("203.0.113.8")).isNotEqualTo(today);

        clock.now = clock.instant().plus(Duration.ofMinutes(2));
        assertThat(keys.guest("203.0.113.7")).as("a new UTC day cannot be linked to the last one").isNotEqualTo(today);
    }

    @Test
    void anotherSecretGivesOtherGuestKeys() {
        assertThat(new QuotaKeys("other-secret", clock).guest("203.0.113.7")).isNotEqualTo(keys.guest("203.0.113.7"));
    }

    @Test
    void aUserKeyNamesTheUserAndTheDayAndBothLiveKeysCanBeFound() {
        UUID user = UUID.fromString("6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12");

        assertThat(keys.user(user)).isEqualTo("h2:v1:quota:user:" + user + ":2026-10-30");
        assertThat(keys.userKeys(user)).containsExactly(
            "h2:v1:quota:user:" + user + ":2026-10-30", "h2:v1:quota:user:" + user + ":2026-10-29");
    }

    @Test
    void theGlobalKeyIsOnePerUtcDay() {
        assertThat(keys.global()).isEqualTo("h2:v1:quota:global:2026-10-30");
    }

    @Test
    void aQueryKeyIsStableAcrossDaysAndHoldsNoText() {
        byte[] key = keys.queryKey("tavily|rag frameworks");
        clock.now = clock.instant().plus(Duration.ofDays(3));

        assertThat(keys.queryKey("tavily|rag frameworks")).isEqualTo(key).hasSize(32);
        assertThat(keys.queryKey("tavily|rag framework")).isNotEqualTo(key);
        assertThat(new QuotaKeys("other-secret", clock).queryKey("tavily|rag frameworks")).isNotEqualTo(key);
    }

    @Test
    void withoutASecretNoPseudonymCanBeMade() {
        QuotaKeys none = new QuotaKeys("", clock);

        assertThat(none.hasSecret()).isFalse();
        assertThatThrownBy(() -> none.guest("203.0.113.7")).isInstanceOf(IllegalStateException.class).hasMessageContaining("H2_QUOTA_KEY_SECRET");
        assertThatThrownBy(() -> none.queryKey("x")).isInstanceOf(IllegalStateException.class);
    }
}
