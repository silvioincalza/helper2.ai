package ai.helper2.guide.internal;

final class DeadlineTimeout extends RuntimeException {

  DeadlineTimeout(String step) {
    super("the " + step + " call reached the time the question had left for it");
  }
}
