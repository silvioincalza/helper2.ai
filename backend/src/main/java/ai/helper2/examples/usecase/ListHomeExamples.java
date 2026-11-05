package ai.helper2.examples.usecase;

import ai.helper2.examples.HomeExample;
import ai.helper2.examples.HomeExamples;
import java.util.List;
import org.springframework.stereotype.Service;

/** The examples the home page may show: active and passing the check. */
@Service
public class ListHomeExamples {

  /** The page picks four; the endpoint never sends more than this. */
  public static final int MAX_SERVED = 50;

  private final HomeExamples examples;

  public ListHomeExamples(HomeExamples examples) {
    this.examples = examples;
  }

  /** The examples of [locale]; the English ones when it has none (the UI ships in English). */
  public List<HomeExample> served(String locale) {
    List<HomeExample> served = examples.served(locale, MAX_SERVED);
    return served.isEmpty() && !AddHomeExample.LOCALE.equals(locale)
        ? examples.served(AddHomeExample.LOCALE, MAX_SERVED)
        : served;
  }
}
