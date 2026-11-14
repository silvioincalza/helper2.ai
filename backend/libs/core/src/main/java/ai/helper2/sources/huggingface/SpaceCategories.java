package ai.helper2.sources.huggingface;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

final class SpaceCategories {

  private record Rule(String category, List<Set<String>> wordGroups) {
    boolean fits(Set<String> words) {
      return wordGroups.stream().allMatch(group -> group.stream().anyMatch(words::contains));
    }
  }

  private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

  private static final Set<String> MAKING =
      Set.of("make", "making", "create", "generate", "generation", "generator");

  private static final List<Rule> RULES =
      List.of(
          new Rule(
              "background-removal",
              List.of(
                  Set.of("background", "backgrounds"),
                  Set.of("remove", "removal", "remover", "removing"))),
          new Rule(
              "image-generation",
              List.of(Set.of("image", "images", "picture", "pictures", "logo", "logos"), MAKING)),
          new Rule("music-generation", List.of(Set.of("music", "song", "songs"), MAKING)));

  private static final String SPEECH_SYNTHESIS = "speech-synthesis";

  private static final List<String> SPEECH_PHRASES = List.of(" text to speech ", " tts ");

  private SpaceCategories() {}

  static Optional<String> of(String text) {
    String padded =
        " " + NON_WORD.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").trim() + " ";
    if (SPEECH_PHRASES.stream().anyMatch(padded::contains)) {
      return Optional.of(SPEECH_SYNTHESIS);
    }
    Set<String> words = Set.copyOf(Arrays.asList(padded.trim().split(" ")));
    return RULES.stream().filter(r -> r.fits(words)).map(Rule::category).findFirst();
  }
}
