package ai.searchavo.examples.usecase;

import ai.searchavo.examples.HomeExample;
import ai.searchavo.examples.ServedExamples;
import java.util.List;

public class ListHomeExamples {
  public static final int MAX_SERVED = 50;

  private final ServedExamples examples;

  public ListHomeExamples(ServedExamples examples) {
    this.examples = examples;
  }

  public List<HomeExample> served(String locale) {
    List<HomeExample> served = examples.served(locale, MAX_SERVED);
    return served.isEmpty() && !AddHomeExample.LOCALE.equals(locale)
        ? examples.served(AddHomeExample.LOCALE, MAX_SERVED)
        : served;
  }
}
