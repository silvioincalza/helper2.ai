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
                CanonicalUrl.of("https://example.com/blog/how-we-built-it"), "How we built it"))
        .isNull();
  }

  private static String site(String url) {
    CanonicalUrl found = DiscoveryPages.productSite(CanonicalUrl.of(url));
    return found == null ? null : found.getValue();
  }
}
