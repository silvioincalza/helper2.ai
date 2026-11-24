package ai.searchavo.sources.liveweb.scout;

import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.sources.liveweb.scout.CourseCollector.Platform;
import ai.searchavo.sources.support.Classification;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public final class CoursePages {

  private static final Pattern COURSE_WORDS =
      Pattern.compile(
          "\\b(courses?|certifications?|certificates?|certified|specializations?|training|bootcamp|exam|class|learning path|"
              + "nanodegree|workshop|live event|tutorials?|lessons?)\\b",
          Pattern.CASE_INSENSITIVE);

  private final List<Platform> platforms;

  public CoursePages(List<String> platformSpecs) {
    this.platforms = platformSpecs.stream().map(Platform::parse).filter(Objects::nonNull).toList();
  }

  List<Platform> platforms() {
    return platforms;
  }

  public Optional<String> platformOfCourse(String title, @Nullable String text, CanonicalUrl url) {
    return platforms.stream()
        .filter(p -> p.covers(url))
        .findFirst()
        .filter(p -> isAiCourse(title, text, url))
        .map(Platform::name);
  }

  static boolean isAiCourse(String title, @Nullable String text, CanonicalUrl url) {
    if (!Classification.isAiRelated(title, text, Set.of())) {
      return false;
    }
    String words =
        title
            + " "
            + (text == null ? "" : text)
            + " "
            + url.getValue().replace('-', ' ').replace('/', ' ');
    return COURSE_WORDS.matcher(words).find();
  }
}
