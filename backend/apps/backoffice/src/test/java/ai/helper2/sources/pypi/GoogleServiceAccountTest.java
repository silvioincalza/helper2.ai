package ai.helper2.sources.pypi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GoogleServiceAccountTest {

  static final String GOOGLE = "https://oauth2.googleapis.com/token";

  @Test
  void aBase64KeyFileGivesTheAccountAndItsPrivateKey() {
    GoogleServiceAccount account =
        GoogleServiceAccount.fromBase64Json(GoogleKeys.base64(GoogleKeys.keyJson(GOOGLE)));

    assertThat(account.clientEmail()).isEqualTo("scout@helper2ai.iam.gserviceaccount.com");
    assertThat(account.privateKeyId()).isEqualTo("key-1");
    assertThat(account.privateKey().getEncoded())
        .isEqualTo(GoogleKeys.PAIR.getPrivate().getEncoded());
  }

  @Test
  void itsTextNamesTheAccountButNeverTheKey() {
    GoogleServiceAccount account =
        GoogleServiceAccount.fromBase64Json(GoogleKeys.base64(GoogleKeys.keyJson(GOOGLE)));

    assertThat(account.toString())
        .contains("scout@helper2ai.iam.gserviceaccount.com")
        .doesNotContain("PRIVATE")
        .doesNotContain("modulus");
  }

  @Test
  void aKeyThatSendsTheSignInElsewhereIsRefused() {
    String other = GoogleKeys.base64(GoogleKeys.keyJson("https://evil.example/token"));

    assertThatThrownBy(() -> GoogleServiceAccount.fromBase64Json(other))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("token_uri");
  }

  @Test
  void somethingThatIsNotABase64KeyFileIsRefusedWithoutEchoingIt() {
    assertThatThrownBy(() -> GoogleServiceAccount.fromBase64Json("not-base64-%%%"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("not-base64");
    assertThatThrownBy(() -> GoogleServiceAccount.fromBase64Json(GoogleKeys.base64("{}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("service account");
  }
}
