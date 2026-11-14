package ai.helper2.identity.config;

public record SignInPages(String signedIn, String failed) {
  public static SignInPages at(String page) {
    return new SignInPages(page, page + "?signin=failed");
  }
}
