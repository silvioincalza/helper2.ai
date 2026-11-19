package ai.helper2.sources.config;

import ai.helper2.search.SourceDescriptor;
import ai.helper2.shared.CollectorIds;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.sources.tavily.DiscoveryCategory;
import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class CollectorDescriptors {

  private static final URI SOURCES_PAGE = URI.create("https://helper2.ai/sources");
  private static final URI TERMS_PAGE = URI.create("https://helper2.ai/terms");

  private CollectorDescriptors() {}

  public static SourceDescriptor aiDeadlines(SourceSettings settings) {
    return SourceDescriptor.external(
            CollectorIds.AI_DEADLINES,
            "AI conference deadlines",
            "Conferences",
            SourceMode.INDEXED,
            URI.create("https://github.com/huggingface/ai-deadlines"),
            EnumSet.of(ResourceType.EVENT),
            settings.timeout(),
            settings.cacheTtl())
        .withTerms(URI.create("https://github.com/huggingface/ai-deadlines/blob/main/LICENSE"))
        .withAttribution("Conference dates and venues from huggingface/ai-deadlines (MIT License)")
        .enabledWhen(settings.enabled())
        .withPriority(40);
  }

  public static SourceDescriptor confsTech(SourceSettings settings) {
    return SourceDescriptor.external(
            CollectorIds.CONFS_TECH,
            "Tech conferences (confs.tech)",
            "confs.tech",
            SourceMode.INDEXED,
            URI.create("https://confs.tech"),
            EnumSet.of(ResourceType.EVENT),
            settings.timeout(),
            settings.cacheTtl())
        .withTerms(
            URI.create("https://github.com/tech-conferences/conference-data/blob/main/LICENSE.md"))
        .withAttribution(
            "Conference dates and places from confs.tech, tech-conferences/conference-data (MIT License)")
        .enabledWhen(settings.enabled())
        .withPriority(40);
  }

  public static SourceDescriptor gitHubAwesome(SourceSettings settings) {
    return SourceDescriptor.external(
            CollectorIds.GITHUB_AWESOME,
            "GitHub Awesome lists",
            "Awesome",
            SourceMode.INDEXED,
            URI.create("https://github.com/topics/awesome-list"),
            EnumSet.of(
                ResourceType.ARTICLE,
                ResourceType.LIBRARY,
                ResourceType.MCP_SERVER,
                ResourceType.AGENT,
                ResourceType.PROMPT,
                ResourceType.SKILL,
                ResourceType.MODEL,
                ResourceType.DATASET,
                ResourceType.PRODUCT,
                ResourceType.COURSE,
                ResourceType.AWESOME_LIST),
            settings.timeout(),
            settings.cacheTtl())
        .withTerms(
            URI.create(
                "https://docs.github.com/en/site-policy/github-terms/github-terms-of-service"))
        .withAttribution(
            "Repository metadata from the GitHub API; list headings and entry lines are indexed for"
                + " search, never stored. Lists linked from lists are followed. A linked Hugging"
                + " Face model is read from the Hugging Face Hub API; links to course pages and"
                + " product sites are added only after a person checks them.")
        .enabledWhen(settings.enabled() && settings.hasToken())
        .withPriority(25);
  }

  public static SourceDescriptor gitHubTopicLeaders(SourceSettings settings, List<String> topics) {
    return SourceDescriptor.external(
            CollectorIds.GITHUB_TOPIC_LEADERS,
            "GitHub topic leaders",
            "Top repos",
            SourceMode.INDEXED,
            URI.create("https://github.com/topics"),
            EnumSet.of(
                ResourceType.LIBRARY,
                ResourceType.AGENT,
                ResourceType.MCP_SERVER,
                ResourceType.MODEL,
                ResourceType.PRODUCT),
            settings.timeout(),
            settings.cacheTtl())
        .withTerms(
            URI.create(
                "https://docs.github.com/en/site-policy/github-terms/github-terms-of-service"))
        .withAttribution(
            "Repository metadata from the GitHub API: the most-starred repositories of the topics "
                + String.join(", ", topics)
                + ". The ones with many stars and a recent change are added as they are found,"
                + " the others only after a person checks them.")
        .enabledWhen(settings.enabled() && settings.hasToken())
        .withPriority(25);
  }

  public static SourceDescriptor gitHubSdks(
      SourceSettings settings, List<String> languages, List<String> orgs) {
    return SourceDescriptor.external(
            CollectorIds.GITHUB_SDKS,
            "AI libraries and SDKs",
            "SDKs",
            SourceMode.INDEXED,
            URI.create("https://deps.dev"),
            EnumSet.of(ResourceType.LIBRARY, ResourceType.AGENT, ResourceType.MCP_SERVER),
            settings.timeout(),
            settings.cacheTtl())
        .withTerms(
            URI.create(
                "https://docs.github.com/en/site-policy/github-terms/github-terms-of-service"))
        .withAttribution(
            "Repository metadata from the GitHub API (AI repos per language: "
                + String.join(", ", languages)
                + "; and the repos of "
                + String.join(", ", orgs)
                + ") and package data from deps.dev (Open Source Insights, CC-BY 4.0),"
                + " added only after a person checks them.")
        .enabledWhen(settings.enabled() && settings.hasToken())
        .withPriority(25);
  }

  public static SourceDescriptor hfProviderOrgs(SourceSettings settings, List<String> orgs) {
    return SourceDescriptor.external(
            CollectorIds.HF_PROVIDER_ORGS,
            "Hugging Face model makers",
            "HF makers",
            SourceMode.INDEXED,
            URI.create("https://huggingface.co/models"),
            EnumSet.of(ResourceType.MODEL),
            settings.timeout(),
            settings.cacheTtl())
        .withTerms(URI.create("https://huggingface.co/terms-of-service"))
        .withAttribution(
            "Newest models of AI providers' official Hugging Face organizations: "
                + String.join(", ", orgs))
        .enabledWhen(settings.enabled())
        .withPriority(30);
  }

  public static SourceDescriptor hfTrending(SourceSettings settings) {
    return SourceDescriptor.external(
            CollectorIds.HF_TRENDING,
            "Hugging Face trending",
            "HF trending",
            SourceMode.INDEXED,
            URI.create("https://huggingface.co/models?sort=trending"),
            EnumSet.of(ResourceType.MODEL),
            settings.timeout(),
            settings.cacheTtl())
        .withTerms(URI.create("https://huggingface.co/terms-of-service"))
        .withAttribution("Model metadata from the Hugging Face Hub API")
        .enabledWhen(settings.enabled())
        .withPriority(30);
  }

  public static SourceDescriptor mcpRegistry(SourceSettings settings) {
    return SourceDescriptor.external(
            CollectorIds.MCP_REGISTRY,
            "Official MCP Registry",
            "MCP",
            SourceMode.INDEXED,
            URI.create("https://registry.modelcontextprotocol.io"),
            EnumSet.of(ResourceType.MCP_SERVER),
            settings.timeout(),
            settings.cacheTtl())
        .withTerms(URI.create("https://github.com/modelcontextprotocol/registry"))
        .withAttribution("This data comes from the Official MCP Registry (CC0).")
        .enabledWhen(settings.enabled())
        .withPriority(25);
  }

  public static SourceDescriptor pypi(SourceSettings settings) {
    return SourceDescriptor.external(
            CollectorIds.PYPI,
            "PyPI (Python packages)",
            "PyPI",
            SourceMode.INDEXED,
            URI.create("https://pypi.org"),
            EnumSet.of(ResourceType.LIBRARY),
            settings.timeout(),
            settings.cacheTtl())
        .withTerms(URI.create("https://docs.pypi.org/api/bigquery/"))
        .withAttribution(
            "Python packages with the AI classifier, from PyPI's public package metadata"
                + " (Google BigQuery dataset bigquery-public-data.pypi).")
        .enabledWhen(settings.enabled())
        .withPriority(30);
  }

  public static SourceDescriptor liveWebScout(SourceSettings settings, List<String> queries) {
    return SourceDescriptor.external(
            CollectorIds.LIVE_WEB_SCOUT,
            "AI news",
            "News",
            SourceMode.INDEXED,
            SOURCES_PAGE,
            EnumSet.of(ResourceType.NEWS, ResourceType.ARTICLE, ResourceType.EVENT),
            settings.timeout(),
            settings.cacheTtl())
        .withTerms(TERMS_PAGE)
        .withAttribution(
            "Web results found by a live web search; each links to the original publisher.")
        .enabledWhen(settings.enabled() && settings.hasToken() && !queries.isEmpty())
        .withPriority(48);
  }

  public static SourceDescriptor courses(SourceSettings settings, String query, List<?> platforms) {
    return SourceDescriptor.external(
            CollectorIds.COURSES,
            "Courses and certifications",
            "Courses",
            SourceMode.INDEXED,
            SOURCES_PAGE,
            EnumSet.of(ResourceType.COURSE),
            settings.timeout(),
            settings.cacheTtl())
        .withTerms(TERMS_PAGE)
        .withAttribution(
            "Course and certification pages found by a live web search; an admin reviews each before it is cataloged.")
        .enabledWhen(
            settings.enabled() && settings.hasToken() && !platforms.isEmpty() && !query.isEmpty())
        .withPriority(60);
  }

  public static SourceDescriptor liveWebEvents(
      SourceSettings settings, List<String> queries, boolean readerOn) {
    return SourceDescriptor.external(
            CollectorIds.LIVE_WEB_EVENTS,
            "Upcoming AI events",
            "Events",
            SourceMode.INDEXED,
            SOURCES_PAGE,
            EnumSet.of(ResourceType.EVENT),
            settings.timeout(),
            settings.cacheTtl())
        .withTerms(TERMS_PAGE)
        .withAttribution(
            "Event pages found by a live web search; an AI model reads each page's days, and code"
                + " checks them against the page's own words. Each links to the event's page.")
        .enabledWhen(liveWebEventsOn(settings, queries, readerOn))
        .withPriority(48);
  }

  public static boolean liveWebEventsOn(
      SourceSettings settings, List<String> queries, boolean readerOn) {
    return settings.enabled() && settings.hasToken() && !queries.isEmpty() && readerOn;
  }

  public static SourceDescriptor linkedIn(SourceSettings settings, List<String> queries) {
    return SourceDescriptor.external(
            CollectorIds.LINKEDIN,
            "LinkedIn live web",
            "LinkedIn",
            SourceMode.INDEXED,
            URI.create("https://www.linkedin.com"),
            EnumSet.of(
                ResourceType.ARTICLE, ResourceType.NEWS, ResourceType.PRODUCT, ResourceType.EVENT),
            settings.timeout(),
            settings.cacheTtl())
        .withTerms(TERMS_PAGE)
        .withAttribution(
            "Public LinkedIn pages found by a live web search; each links to LinkedIn. An admin"
                + " reviews each before it is cataloged.")
        .enabledWhen(settings.enabled() && settings.hasToken() && !queries.isEmpty())
        .withPriority(60);
  }

  public static SourceDescriptor discovery(SourceSettings settings, DiscoveryCategory category) {
    boolean enabled = settings.enabled() && category.enabled();
    return SourceDescriptor.external(
            category.sourceId(),
            category.name(),
            category.shortName(),
            SourceMode.INDEXED,
            SOURCES_PAGE,
            discoveryTypes(category),
            settings.timeout(),
            settings.cacheTtl())
        .withTerms(TERMS_PAGE)
        .withAttribution(
            category.indexLists()
                ? "Web results found by a live web search; each links to the original publisher."
                    + " List articles are added without review; products and articles about them"
                    + " are added only after a person checks them."
                : "Web results found by a live web search; each links to the original publisher."
                    + " Products and articles about them are added only after a person checks"
                    + " them.")
        .enabledWhen(enabled && settings.hasToken())
        .withPriority(50)
        .inFamily(category.family().id());
  }

  public static SourceDescriptor newsFeed(SourceSettings settings, FeedProperties.Feed feed) {
    return SourceDescriptor.external(
            feed.sourceId(),
            feed.publisher() + " news",
            feed.shortName(),
            SourceMode.INDEXED,
            feed.homepage(),
            EnumSet.of(ResourceType.NEWS),
            settings.timeout(),
            settings.cacheTtl())
        .withAttribution(
            "Headlines from "
                + feed.publisher()
                + "'s own news feed: the title, the date and the feed's short summary,"
                + " each linking to the original post.")
        .enabledWhen(settings.enabled() && feed.isEnabled())
        .withPriority(45);
  }

  private static Set<ResourceType> discoveryTypes(DiscoveryCategory category) {
    return EnumSet.of(category.type(), ResourceType.ARTICLE);
  }
}
