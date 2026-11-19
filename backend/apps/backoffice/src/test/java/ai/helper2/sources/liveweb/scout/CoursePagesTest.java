package ai.helper2.sources.liveweb.scout;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.shared.CanonicalUrl;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoursePagesTest {

  final CoursePages pages =
      new CoursePages(List.of("coursera.org=Coursera", "huggingface.co/learn=Hugging Face Learn"));

  static CanonicalUrl url(String raw) {
    return CanonicalUrl.of(raw);
  }

  @Test
  void aCoursePageAboutAiOnAPlatformNamesItsPlatform() {
    assertThat(
            pages.platformOfCourse(
                "Generative AI for everyone",
                "Courses",
                url("https://www.coursera.org/learn/generative-ai-for-everyone")))
        .contains("Coursera");
  }

  @Test
  void aPageOutsideThePlatformsPathIsNoCourse() {
    assertThat(
            pages.platformOfCourse(
                "LLM course", "Courses", url("https://huggingface.co/blog/llm-course")))
        .isEmpty();
    assertThat(
            pages.platformOfCourse(
                "LLM course", "Courses", url("https://huggingface.co/learn/llm-course")))
        .contains("Hugging Face Learn");
  }

  @Test
  void aPageThatIsNotAboutAiOrDoesNotReadLikeACourseIsNoCourse() {
    assertThat(
            pages.platformOfCourse(
                "Cooking course", null, url("https://coursera.org/learn/cooking")))
        .isEmpty();
    assertThat(
            pages.platformOfCourse(
                "Generative AI", null, url("https://coursera.org/articles/generative-ai")))
        .isEmpty();
  }
}
