package ai.helper2.identity.config;

/**
 * Where a sign-in returns, set by each app (ADR 0069): the public site's start page, or the back
 * office. The success handler and the session store's failure path both use it.
 *
 * @param signedIn after a successful sign-in
 * @param failed after a failed one (it carries {@code ?signin=failed})
 */
public record SignInPages(String signedIn, String failed) {

  /** Both pages from one address: [page], and [page] with {@code ?signin=failed}. */
  public static SignInPages at(String page) {
    return new SignInPages(page, page + "?signin=failed");
  }
}
