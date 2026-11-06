package ai.helper2.sources.tavily;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.shared.CanonicalUrl;
import org.junit.jupiter.api.Test;

class DiscoveryPagesTest {

  @Test
  void aProductSiteIsAHomePageOrOneLevelBelowItButNotWritingAboutOne() {
    assertThat(site("https://cursor.com/")).isEqualTo("https://cursor.com");
    assertThat(site("https://cursor.com/features")).isEqualTo("https://cursor.com");
    // A big vendor has one product per page: the page is the product, not the company.
    assertThat(site("https://aws.amazon.com/bedrock")).isEqualTo("https://aws.amazon.com/bedrock");
    // A language segment does not count.
    assertThat(site("https://deepl.com/en")).isEqualTo("https://deepl.com/en");
    assertThat(site("https://deepl.com/de/pricing")).isEqualTo("https://deepl.com");
    assertThat(site("https://deepl.com/en/blog/new-model")).isNull();
    // AI directories link products; they are not one.
    assertThat(site("https://theresanaiforthat.com/")).isNull();
    assertThat(site("https://cursor.com/blog")).isNull();
    assertThat(site("https://cursor.com/docs/agent")).isNull();
    assertThat(site("https://cursor.com/why-we-rebuilt-our-editor")).isNull();
    assertThat(site("https://docs.cursor.com/")).isNull();
    assertThat(site("https://someone.substack.com/")).isNull();
    assertThat(site("https://github.com/ollama/ollama"))
        .isEqualTo("https://github.com/ollama/ollama");
    assertThat(site("https://github.com/ollama")).isNull();
    assertThat(site("https://github.com/topics/llm")).isNull();
  }

  @Test
  void aListTitleCompares() {
    CanonicalUrl page = CanonicalUrl.of("https://example.com/some-page");
    assertThat(DiscoveryPages.isListPage("12 Best AI Writing Tools", page)).isTrue();
    assertThat(DiscoveryPages.isListPage("Best AI coding tools in 2026", page)).isTrue();
    assertThat(DiscoveryPages.isListPage("Cursor vs Windsurf", page)).isTrue();
    assertThat(DiscoveryPages.isListPage("Top ChatGPT alternatives", page)).isTrue();
    assertThat(DiscoveryPages.isListPage("Acme – the best way to run LLMs", page)).isFalse();
    assertThat(DiscoveryPages.isListPage("An open-source alternative to ChatGPT", page)).isFalse();
  }

  @Test
  void theTitleOfAPageBelowTheHomePageIsTheSiteName() {
    CanonicalUrl site = CanonicalUrl.of("https://anythingllm.com/");
    CanonicalUrl pricing = CanonicalUrl.of("https://anythingllm.com/pricing");
    assertThat(DiscoveryPages.siteTitle("Pricing | AnythingLLM", pricing, site))
        .isEqualTo("AnythingLLM");
    assertThat(DiscoveryPages.siteTitle("AnythingLLM - Pricing", pricing, site))
        .isEqualTo("AnythingLLM");
    assertThat(DiscoveryPages.siteTitle("Plans and prices", pricing, site))
        .isEqualTo("anythingllm.com");
  }

  @Test
  void aRepositoryOrAHomePageIsAProductWhateverItsTitleSays() {
    DiscoveryPages.Page repo =
        DiscoveryPages.classify(
            CanonicalUrl.of("https://github.com/danny-avila/LibreChat"),
            "Top 10 alternatives to ChatGPT");
    assertThat(repo.shape()).isEqualTo(DiscoveryPages.Shape.PRODUCT);
    DiscoveryPages.Page list =
        DiscoveryPages.classify(
            CanonicalUrl.of("https://example.com/blog/12-best-ai-tools"), "12 Best AI Tools");
    assertThat(list.shape()).isEqualTo(DiscoveryPages.Shape.LIST);
    assertThat(
            DiscoveryPages.classify(
                    CanonicalUrl.of("https://example.com/blog/how-we-built-it"), "How we built it")
                .shape())
        .isEqualTo(DiscoveryPages.Shape.ARTICLE);
  }

  /** ADR 0062: a post about a product is kept as an article, under its own link. */
  @Test
  void aPostAboutAProductIsAnArticle() {
    String engadget =
        "https://www.engadget.com/2300677/google-gemini-carbon-promises-significant-leap-in-ai-coding";
    DiscoveryPages.Page page =
        DiscoveryPages.classify(
            CanonicalUrl.of(engadget), " Google Gemini Carbon promises a leap ");
    assertThat(page.shape()).isEqualTo(DiscoveryPages.Shape.ARTICLE);
    assertThat(page.url()).isEqualTo(CanonicalUrl.of(engadget));
    assertThat(page.title()).isEqualTo("Google Gemini Carbon promises a leap");
    assertThat(writing("https://blog.acme.ai/introducing-acme-two")).isTrue();
    assertThat(writing("https://acme.ai/news/acme-two")).isTrue();
    assertThat(writing("https://techcrunch.com/2026/11/02/acme-launches/")).isTrue();
    assertThat(writing("https://someone.substack.com/p/claude-review")).isTrue();
    assertThat(writing("https://cursor.com/why-we-rebuilt-our-editor")).isTrue();
  }

  @Test
  void otherPagesAreNotArticles() {
    // The index of a blog, a publisher's home page: no post.
    assertThat(writing("https://cursor.com/blog")).isFalse();
    assertThat(writing("https://someone.substack.com/")).isFalse();
    assertThat(writing("https://medium.com/@someone")).isFalse();
    // Docs, careers and legal pages, and directories that list products.
    assertThat(writing("https://cursor.com/docs/agent-mode-and-tools")).isFalse();
    assertThat(writing("https://docs.cursor.com/how-to-use-the-agent")).isFalse();
    assertThat(writing("https://acme.ai/careers/senior-ml-engineer-zurich")).isFalse();
    assertThat(writing("https://www.producthunt.com/posts/acme-ai-writer")).isFalse();
    assertThat(writing("https://github.com/acme/acme-ai-writer-tool")).isFalse();
    assertThat(writing("https://acme.ai/pricing")).isFalse();
  }

  private static boolean writing(String url) {
    return DiscoveryPages.isWritingPage(CanonicalUrl.of(url));
  }

  private static String site(String url) {
    CanonicalUrl found = DiscoveryPages.productSite(CanonicalUrl.of(url));
    return found == null ? null : found.getValue();
  }
}
