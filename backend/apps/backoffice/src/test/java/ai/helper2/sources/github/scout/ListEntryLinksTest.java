package ai.helper2.sources.github.scout;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.scout.SeedDiscoveryHosts;
import ai.helper2.search.ShadowLibraries;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.sources.github.scout.ListEntryLinks.Article;
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
        new ListEntryLinks.Readers(readsModels, readsModels, readsModels));
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
  void uc030Br008AProductACourseAndAnArticleAreNamedByTheAddressTheirCandidateHas() {
    assertThat(
            links.reviewedPage(
                link(
                    "https://www.coursera.org/learn/generative-ai-for-everyone",
                    "Generative AI for everyone",
                    "Courses"),
                PAGES))
        .isEqualTo(CanonicalUrl.of("https://coursera.org/learn/generative-ai-for-everyone"));
    assertThat(
            links.reviewedPage(
                link("https://acme.ai/blog/how-we-built-our-rag-stack", "A post", "R"), PAGES))
        .isEqualTo(CanonicalUrl.of("https://acme.ai/blog/how-we-built-our-rag-stack"));
    assertThat(links.reviewedPage(link("https://cursor.com/?ref=list", "Cursor", "E"), PAGES))
        .isEqualTo(CanonicalUrl.of("https://cursor.com"));
  }

  @Test
  void uc030Br008APageASourceConfirmsAndASkippedLinkAreNoReviewedPage() {
    assertThat(
            links.reviewedPage(
                link("https://huggingface.co/acme/voice-model", "Voice", "M"), PAGES))
        .isNull();
    assertThat(
            links(false)
                .reviewedPage(link("https://huggingface.co/acme/voice-model", "Voice", "M"), PAGES))
        .isNull();
    assertThat(links.reviewedPage(link("https://acme.ai/blog", "The blog", "R"), PAGES)).isNull();
    assertThat(links.reviewedPage(link("http://10.0.0.1/admin", "Admin", "R"), PAGES)).isNull();
  }

  @ParameterizedTest
  @CsvSource({
    "https://acme.ai/blog/how-we-built-our-rag-stack",
    "https://medium.com/@someone/a-guide-to-ai-agents",
    "https://newsletter.ai/how-we-built-our-ai-stack",
  })
  void uc030Br007ALinkThatReadsAsAnArticleByTheRuleOfDiscoveryIsAnArticleForReview(String url) {
    assertThat(route(url + "?utm_source=list", "How we built it", "Reading"))
        .isEqualTo(new Article(CanonicalUrl.of(url)));
  }

  @ParameterizedTest
  @CsvSource({
    "https://acme.ai/blog, not-a-product-page",
    "https://medium.com/@someone, not-a-product-page",
    "https://producthunt.com/stories/how-we-rank-ai-tools, not-a-product-page",
    "https://arxiv.org/list/cs.AI/recent-papers-of-the-week, excluded-domain",
  })
  void uc030Br007ABlogIndexAnAuthorPageADirectoryPageAndAnExcludedDomainAreNoArticle(
      String url, String reason) {
    assertThat(route(url, "Read more", "Reading")).isEqualTo(new Skipped(reason));
  }

  @Test
  void anyOtherLinkIsAProductSiteForReview() {
    assertThat(route("https://acme-voice.ai/pricing?plan=pro", "Acme Voice", "Tools"))
        .isEqualTo(new Product(CanonicalUrl.of("https://acme-voice.ai")));
  }

  @ParameterizedTest
  @CsvSource({
    "https://arxiv.org/list/cs.AI/recent, excluded-domain",
    "https://arxiv.org/abs/not-an-id, excluded-domain",
    "https://pypi.org/project/transformers, excluded-domain",
    "https://www.npmjs.com/~someone, excluded-domain",
    "https://www.npmjs.com/package/ai/access, excluded-domain",
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
  void anArxivPaperGoesToArxivByItsIdWhateverTheViewAndTheVersion() {
    ListEntryLinks.Paper paper =
        new ListEntryLinks.Paper("1706.03762", CanonicalUrl.of("https://arxiv.org/abs/1706.03762"));

    assertThat(route("https://arxiv.org/abs/1706.03762v5", "Attention", "Papers")).isEqualTo(paper);
    assertThat(route("https://arxiv.org/pdf/1706.03762.pdf", "pdf", "Papers")).isEqualTo(paper);
    assertThat(route("https://huggingface.co/papers/1706.03762", "Attention", "Papers"))
        .isEqualTo(paper);
  }

  @Test
  void anNpmPackagePageGoesToTheRegistryByItsName() {
    assertThat(route("https://www.npmjs.com/package/@langchain/core", "core", "SDKs"))
        .isEqualTo(
            new ListEntryLinks.Package(
                "@langchain/core", CanonicalUrl.of("https://npmjs.com/package/@langchain/core")));
    assertThat(route("https://www.npmjs.com/package/ai", "ai", "SDKs"))
        .isEqualTo(
            new ListEntryLinks.Package("ai", CanonicalUrl.of("https://npmjs.com/package/ai")));
  }

  @Test
  void withoutTheirReadersAPaperAndAPackageFollowTheRuleOfOtherLinks() {
    assertThat(links(false).route(link("https://arxiv.org/abs/1706.03762", "A", "P"), PAGES))
        .isEqualTo(new Skipped("excluded-domain"));
    assertThat(links(false).route(link("https://www.npmjs.com/package/ai", "ai", "S"), PAGES))
        .isEqualTo(new Skipped("excluded-domain"));
  }

  @Test
  void withoutAHubReaderAModelLinkFollowsTheRuleOfOtherLinks() {
    assertThat(links(false).route(link("https://huggingface.co/acme/voice-model", "V", "M"), PAGES))
        .isEqualTo(new Skipped("excluded-domain"));
  }

  @Test
  void theModelPageOfALinkIsKnownWithoutAnyCall() {
    assertThat(links.confirmablePage(link("https://hf.co/acme/voice-model", "V", "M")))
        .isEqualTo(CanonicalUrl.of("https://huggingface.co/acme/voice-model"));
    assertThat(links.confirmablePage(link("https://acme.ai", "V", "M"))).isNull();
    assertThat(links.confirmablePage(link("http://huggingface.co/acme/voice-model", "V", "M")))
        .isNull();
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
