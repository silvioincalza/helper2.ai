package ai.helper2.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.identity.config.NoProviderTokens;
import ai.helper2.testing.FakeSignInProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "logging.structured.format.console=")
@Import(TestcontainersConfiguration.class)
class SignInKeepsNoTokenIT {

  static final FakeSignInProvider PROVIDER = FakeSignInProvider.start();

  @DynamicPropertySource
  static void provider(DynamicPropertyRegistry registry) {
    PROVIDER.register(registry);
  }

  @AfterAll
  static void stop() {
    PROVIDER.close();
  }

  @LocalServerPort int port;

  @Autowired OAuth2AuthorizedClientRepository signInStore;

  @Autowired ObjectProvider<OAuth2AuthorizedClientService> clientService;

  final StringRedisTemplate redis = TestcontainersConfiguration.adminRedis();

  List<String> sessionValues() {
    List<String> values = new ArrayList<>();
    for (String key : redis.keys("h2:v1:session:backoffice:*")) {
      Map<Object, Object> session = redis.opsForHash().entries(key);
      session.forEach((name, value) -> values.add(name + "=" + value));
    }
    return values;
  }

  @Test
  void aSignInKeepsTheUserButNotTheProvidersToken() throws Exception {
    FakeSignInProvider.SignedIn signedIn = PROVIDER.signIn(URI.create("http://localhost:" + port));

    assertThat(signedIn.status()).isEqualTo(302);
    assertThat(signedIn.location()).as("the sign-in succeeded").endsWith("/admin");
    List<String> values = sessionValues();
    assertThat(values)
        .as("the signed-in user is in the session")
        .anyMatch(v -> v.contains("SPRING_SECURITY_CONTEXT"));
    assertThat(values).noneMatch(v -> v.contains(PROVIDER.accessToken));
    assertThat(values).noneMatch(v -> v.contains("OAuth2AuthorizedClient"));
    for (String profile :
        List.of(
            FakeSignInProvider.EMAIL,
            FakeSignInProvider.BIO,
            FakeSignInProvider.LOCATION,
            FakeSignInProvider.COMPANY,
            "avatars.example")) {
      assertThat(values).as(profile).noneMatch(v -> v.contains(profile));
    }
    assertThat(values).anyMatch(v -> v.contains("Fake User"));
    assertThat(signInStore).isInstanceOf(NoProviderTokens.class);
    OAuth2AuthorizedClientService memory = clientService.getIfAvailable();
    if (memory != null) {
      assertThat(
              memory
                  .<org.springframework.security.oauth2.client.OAuth2AuthorizedClient>
                      loadAuthorizedClient(
                          FakeSignInProvider.REGISTRATION,
                          String.valueOf(FakeSignInProvider.USER_ID)))
          .isNull();
    }
  }
}
