package ai.helper2.sources.github.scout;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.scout.SeedDiscoveryHosts;
import ai.helper2.search.ShadowLibraries;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.sources.github.scout.ListEntryLinks.Course;
import ai.helper2.sources.github.scout.ListEntryLinks.Model;
import ai.helper2.sources.github.scout.ListEntryLinks.Product;
import ai.helper2.sources.github.scout.ListEntryLinks.Route;
import ai.helper2.sources.github.scout.ListEntryLinks.Skipped;
import ai.helper2.sources.liveweb.scout.CoursePages;
import ai.helper2.sources.liveweb.scout.DiscoveryPages;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ListEntryLinksTest {

  private static final DiscoveryPages PAGES = new DiscoveryPages(SeedDiscoveryHosts.LISTS);

  private static final List<String> EXCLUDED =
      List.of("arxiv.org", "pypi.org", "npmjs.com", "huggingface.co", "coursera.org");

  final ListEntryLinks links = links(true);

  static ListEntryLinks links(boolean readsModels) {
    return new ListEntryLinks(
        new ProductLinks(EXCLUDED, ShadowLibraries.of("z-lib")),
        new CoursePages(
            List.of("coursera.org=Coursera", "huggingface.co/learn=Hugging Face Learn")),
        readsModels);
  }

  static AwesomeListParser.Link link(String url, String text, String headingPath) {
    return new AwesomeListParser.Link(url, text, headingPath, headingPath, text);
  }

  Route route(String url, String text, String headingPath) {
    return links.route(link(url, text, headingPath), PAGES);
  }

  @Test
  void aHuggingFaceModelGoesToTheHubWhateverTheExcludedDomainsSay() {
    assertThat(route("https://hf.co/acme/voice-model/tree/main?x=1", "Voice", "Models"))
        .isEqualTo(
            new Model(
                "acme/voice-model", CanonicalUrl.of("https://huggingface.co/acme/voice-model")));
  }

  @Test
  void aCoursePageOnACourseHostIsACourseWhateverTheExcludedDomainsSay() {
    assertThat(
            route(
                "https://www.coursera.org/learn/generative-ai-for-everyone?utm_source=list",
                "Generative AI for everyone",
                "Courses"))
        .isEqualTo(
            new Course(
                CanonicalUrl.of("https://coursera.org/learn/generative-ai-for-everyone"),
                "Coursera"));
    assertThat(route("https://huggingface.co/learn/agents-course", "AI agents course", "Learning"))
        .isEqualTo(
            new Course(
                CanonicalUrl.of("https://huggingface.co/learn/agents-course"),
                "Hugging Face Learn"));
  }

  @Test
  void anyOtherLinkIsAProductSiteForReview() {
    assertThat(route("https://acme-voice.ai/pricing?plan=pro", "Acme Voice", "Tools"))
        .isEqualTo(new Product(CanonicalUrl.of("https://acme-voice.ai")));
  }

  @ParameterizedTest
  @CsvSource({
    "https://arxiv.org/abs/2212.04356, excluded-domain",
    "https://pypi.org/project/transformers, excluded-domain",
    "https://www.npmjs.com/package/ai, excluded-domain",
    "https://huggingface.co/datasets/acme/qa, excluded-domain",
    "https://huggingface.co/spaces/acme/demo, excluded-domain",
    "http://huggingface.co/acme/voice-model, not-https",
    "https://huggingface.co:8443/acme/voice-model, port",
    "https://user@huggingface.co/acme/voice-model, user-info",
    "https://acme.ai/go/huggingface.co/acme/voice-model, redirect",
    "https://docs.acme.ai/guides/start, not-a-product-page",
  })
  void aKindNoReaderConfirmsFollowsTheRuleOfOtherLinksAndAnUnsafeLinkGoesNowhere(
      String url, String reason) {
    assertThat(route(url, "A generative AI course", "Courses")).isEqualTo(new Skipped(reason));
  }

  @Test
  void withoutAHubReaderAModelLinkFollowsTheRuleOfOtherLinks() {
    assertThat(links(false).route(link("https://huggingface.co/acme/voice-model", "V", "M"), PAGES))
        .isEqualTo(new Skipped("excluded-domain"));
  }

  @Test
  void theModelPageOfALinkIsKnownWithoutAnyCall() {
    assertThat(links.modelPage(link("https://hf.co/acme/voice-model", "V", "M")))
        .isEqualTo(CanonicalUrl.of("https://huggingface.co/acme/voice-model"));
    assertThat(links.modelPage(link("https://acme.ai", "V", "M"))).isNull();
    assertThat(links.modelPage(link("http://huggingface.co/acme/voice-model", "V", "M"))).isNull();
  }

  @Test
  void aLinkWhoseWordsNameOnlyAPlaceIsTitledByItsHost() {
    CanonicalUrl site = CanonicalUrl.of("https://acme-voice.ai");

    assertThat(ListEntryLinks.title(link("https://acme-voice.ai", "Website", "T"), site))
        .isEqualTo("acme-voice.ai");
    assertThat(ListEntryLinks.title(link("https://acme-voice.ai", "", "T"), site))
        .isEqualTo("acme-voice.ai");
    assertThat(ListEntryLinks.title(link("https://acme-voice.ai", "Acme Voice", "T"), site))
        .isEqualTo("Acme Voice");
  }
}
