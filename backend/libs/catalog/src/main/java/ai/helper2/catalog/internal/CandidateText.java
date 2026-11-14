package ai.helper2.catalog.internal;

final class CandidateText {

  static final String SELF_DECLARED =
      "Only the product's own page names it (self-declared): confirm it before it shows as verified.";

  static final String UNKNOWN_TARGET =
      "Evidence supports the text, but the target is not a known resource.";

  static final String CHOSEN_RESOURCE =
      "Target chosen by an admin: confirm the evidence supports it before it shows as verified.";

  static final String CHOSEN_NAME =
      "Target named by an admin, not a known resource: confirm the evidence supports it before it"
          + " shows as verified.";

  private CandidateText() {}

  static String reason(String kind, String target) {
    return "Unverified relationship: " + kind + " " + target;
  }
}
