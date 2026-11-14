package ai.helper2.identity.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import tools.jackson.databind.exc.InvalidTypeIdException;

class JsonSessionTest {

  private final RedisSerializer<Object> json =
      SessionConfiguration.springSessionDefaultRedisSerializer();

  @Test
  void aGithubSignInReadsBackWithItsRoles() {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("id", 583231L);
    attributes.put("login", "octocat");
    attributes.put("name", null);
    attributes.put("site_admin", false);
    attributes.put("plan", new LinkedHashMap<>(Map.of("name", "free", "space", 976562499L)));
    attributes.put("scopes", new ArrayList<>(List.of("read:user")));
    var user =
        new DefaultOAuth2User(
            WebSecurity.roles(Set.of("github:583231"), "github", "583231", null, true),
            attributes,
            "id");
    var context =
        new SecurityContextImpl(
            new OAuth2AuthenticationToken(user, user.getAuthorities(), "github"));

    var back = (SecurityContextImpl) json.deserialize(json.serialize(context));

    var token = (OAuth2AuthenticationToken) back.getAuthentication();
    assertThat(token.getAuthorizedClientRegistrationId()).isEqualTo("github");
    assertThat(token.getName()).isEqualTo("583231");
    assertThat(token.getAuthorities())
        .extracting(Object::toString)
        .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    assertThat(token.getPrincipal().getAttributes()).containsEntry("login", "octocat");
  }

  @Test
  void anOidcSignInReadsBack() {
    var idToken =
        new OidcIdToken(
            "token",
            Instant.parse("2026-11-07T08:00:00Z"),
            Instant.parse("2026-11-07T09:00:00Z"),
            Map.of(
                "sub",
                "abc",
                "email",
                "a@example.org",
                "email_verified",
                true,
                "iat",
                Instant.parse("2026-11-07T08:00:00Z"),
                "exp",
                Instant.parse("2026-11-07T09:00:00Z")));
    var user =
        new DefaultOidcUser(
            WebSecurity.roles(Set.of(), "google", "abc", "a@example.org", false), idToken);
    var context =
        new SecurityContextImpl(
            new OAuth2AuthenticationToken(user, user.getAuthorities(), "google"));

    var back = (SecurityContextImpl) json.deserialize(json.serialize(context));

    assertThat(back.getAuthentication().getName()).isEqualTo("abc");
    assertThat(back.getAuthentication().getAuthorities())
        .extracting(Object::toString)
        .containsExactly("ROLE_USER");
    assertThat(((DefaultOidcUser) back.getAuthentication().getPrincipal()).getExpiresAt())
        .isEqualTo(Instant.parse("2026-11-07T09:00:00Z"));
  }

  @Test
  void theSignInRequestReadsBack() {
    var request =
        OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("https://github.com/login/oauth/authorize")
            .clientId("client")
            .redirectUri("https://helper2.ai/login/oauth2/code/github")
            .state("state")
            .attributes(Map.of("registration_id", "github"))
            .build();

    var back = (OAuth2AuthorizationRequest) json.deserialize(json.serialize(request));

    assertThat(back.getState()).isEqualTo("state");
    assertThat(back.getRedirectUri()).isEqualTo("https://helper2.ai/login/oauth2/code/github");
  }

  @Test
  void theSessionTimesReadBackAsTheirTypes() {
    assertThat(json.deserialize(json.serialize(1_762_502_400_000L))).isEqualTo(1_762_502_400_000L);
    assertThat(json.deserialize(json.serialize(1800))).isEqualTo(1800);
  }

  @Test
  void aTypeOutsideTheAllowListIsRefused() {
    byte[] planted =
        "[\"java.lang.ProcessBuilder\",{\"command\":[\"id\"]}]".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> json.deserialize(planted))
        .isInstanceOf(SerializationException.class)
        .hasCauseInstanceOf(InvalidTypeIdException.class);
  }

  @Test
  void aSubclassOfAnAllowedMapOrListIsRefused() {
    String map = PlantedMap.class.getName();
    String list = PlantedList.class.getName();
    byte[] plantedMap = ("[\"" + map + "\",{\"a\":\"b\"}]").getBytes(StandardCharsets.UTF_8);
    byte[] plantedList = ("[\"" + list + "\",[\"a\"]]").getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> json.deserialize(plantedMap))
        .isInstanceOf(SerializationException.class)
        .hasCauseInstanceOf(InvalidTypeIdException.class);
    assertThatThrownBy(() -> json.deserialize(plantedList))
        .isInstanceOf(SerializationException.class)
        .hasCauseInstanceOf(InvalidTypeIdException.class);
  }

  @Test
  void theAllowedMapsAndListsStillReadBack() {
    var map = new HashMap<String, Object>(Map.of("a", "b"));
    var ordered = new LinkedHashMap<String, Object>(Map.of("n", 1L));
    var list = new ArrayList<Object>(List.of("x", 2L));

    assertThat(json.deserialize(json.serialize(map))).isEqualTo(map);
    assertThat(json.deserialize(json.serialize(ordered))).isEqualTo(ordered);
    assertThat(json.deserialize(json.serialize(list))).isEqualTo(list);
  }

  public static class PlantedMap extends HashMap<String, Object> {}

  public static class PlantedList extends ArrayList<Object> {}
}
