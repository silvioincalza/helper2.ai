package ai.helper2.platform.quota;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.platform.ClientKey;
import ai.helper2.platform.llm.RedisCallCounter;
import ai.helper2.platform.resilience.Resilience;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ClientDayQuotaIT {

  private static final LocalDate DAY = LocalDate.of(2026, 11, 12);
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC);
  private static final String VISITOR = "198.51.100.7";
  private static final String NEIGHBOUR = "198.51.100.8";
  private static final String ELSEWHERE = "203.0.113.9";

  @Autowired StringRedisTemplate redis;

  @Autowired Resilience resilience;

  private final QuotaKeys keys = new QuotaKeys("test-secret", CLOCK);
  private final String run = "test-" + UUID.randomUUID() + "-";

  private ClientDayQuota replica(int perClient, int perNetwork) {
    RedisCallCounter counter = new RedisCallCounter(redis, resilience);
    return new ClientDayQuota(
        new ClientBudgets(run + "client:", perClient, CLOCK, keys, counter, 100),
        new ClientBudgets(run + "network:", perNetwork, CLOCK, keys, counter, 100));
  }

  @Test
  void whatIsLeftIsReadFromRedisWithoutTakingATurn() {
    ClientDayQuota quota = replica(3, 100);
    assertThat(quota.limit()).isEqualTo(3);
    assertThat(quota.left(VISITOR)).isEqualTo(3);

    assertThat(quota.take(VISITOR)).isNotNull();

    assertThat(quota.left(VISITOR)).isEqualTo(2);
    assertThat(quota.left(VISITOR)).as("reading does not count").isEqualTo(2);
    assertThat(quota.left(ELSEWHERE)).isEqualTo(3);
  }

  @Test
  void anotherReplicaSeesTheSameCount_andATurnGivenBackIsLeftAgain() {
    ClientDayQuota.Turn turn = replica(2, 100).take(VISITOR);
    ClientDayQuota other = replica(2, 100);

    assertThat(other.left(VISITOR)).isEqualTo(1);

    turn.giveBack();

    assertThat(other.left(VISITOR)).isEqualTo(2);
  }

  @Test
  void whatIsLeftIsTheLowerOfTheVisitorAndItsNetwork() {
    ClientDayQuota quota = replica(20, 1);
    assertThat(quota.take(VISITOR)).isNotNull();

    assertThat(quota.left(NEIGHBOUR)).as("the same /24 has none left").isZero();
    assertThat(quota.take(NEIGHBOUR)).isNull();
    assertThat(quota.left(NEIGHBOUR)).as("a refused turn counts nothing").isZero();
    assertThat(quota.left(ELSEWHERE)).as("another network has its own one").isEqualTo(1);
    assertThat(quota.limit()).as("the limit said is the visitor's own").isEqualTo(20);
  }

  @Test
  void theCountersAreKeptUnderADailyPseudonymForTwoDays() {
    replica(3, 100).take(VISITOR);

    String own = "h2:v1:budget:" + run + "client:" + keys.client(VISITOR) + ":" + DAY;
    String network =
        "h2:v1:budget:" + run + "network:" + keys.client(ClientKey.network(VISITOR)) + ":" + DAY;
    assertThat(redis.opsForValue().get(own)).isEqualTo("1");
    assertThat(redis.opsForValue().get(network)).isEqualTo("1");
    assertThat(own).doesNotContain(VISITOR);
    assertThat(redis.getExpire(own, TimeUnit.SECONDS))
        .isBetween(Duration.ofDays(2).minusMinutes(1).toSeconds(), Duration.ofDays(2).toSeconds());
  }
}
