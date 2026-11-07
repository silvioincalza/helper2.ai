package ai.helper2.sources.config;

import ai.helper2.search.SourceDescriptor;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import ai.helper2.shared.WebSources;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.sources.tavily.DiscoveryCategory;
import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What the Scout's collectors are, as the Sources page and the catalog's source rows state it (ADR
 * 0069). The collectors run in the back office; the public app lists them too (GET
 * /api/v1/sources). Both build the descriptors here, from the same settings, so the two apps state
 * the same names, terms and on/off state, and both write the same rows of the {@code source} table
 * at startup.
 */
public final class CollectorDescriptors {

  public static final String AI_DEADLINES = "ai-deadlines";
  public static final String GITHUB_AWESOME = "github-awesome";
  public static final String HF_PROVIDER_ORGS = "hf-provider-orgs";
  public static final String HF_TRENDING = "hf-trending";
  public static final String MCP_REGISTRY = "mcp-registry";
  public static final String LIVE_WEB_SCOUT = "live-web-scout";
  public static final String COURSES = "course-scout";
  public static final String LIVE_WEB_EVENTS = WebSources.EVENTS_SCOUT;
  public static final String LINKEDIN = "linkedin-scout";

  private static final URI SOURCES_PAGE = URI.create("https://helper2.ai/sources");
  private static final URI TERMS_PAGE = URI.create("https://helper2.ai/terms");

  private CollectorDescriptors() {}

  public static SourceDescriptor aiDeadlines(SourceSettings settings) {
    return new SourceDescriptor(
        AI_DEADLINES,
        "AI conference deadlines",
        "Conferences",
        true,
        SourceMode.INDEXED,
        URI.create("https://github.com/huggingface/ai-deadlines"),
        URI.create("https://github.com/huggingface/ai-deadlines/blob/main/LICENSE"),
        "Conference dates and venues from huggingface/ai-deadlines (MIT License)",
        EnumSet.of(ResourceType.EVENT),
        settings.timeout(),
        settings.cacheTtl(),
        settings.enabled(),
        40);
  }

  public static SourceDescriptor gitHubAwesome(SourceSettings settings) {
    return new SourceDescriptor(
        GITHUB_AWESOME,
        "GitHub Awesome lists",
        "Awesome",
        true,
        SourceMode.INDEXED,
        URI.create("https://github.com/topics/awesome-list"),
        URI.create("https://docs.github.com/en/site-policy/github-terms/github-terms-of-service"),
        "Repository metadata from the GitHub API; list headings and entry lines are indexed for"
            + " search, never stored. Lists linked from lists are followed; links to product"
            + " sites are added only after a person checks them.",
        EnumSet.of(
            ResourceType.ARTICLE,
            ResourceType.LIBRARY,
            ResourceType.MCP_SERVER,
            ResourceType.AGENT,
            ResourceType.PROMPT,
            ResourceType.SKILL,
            ResourceType.MODEL,
            ResourceType.DATASET,
            ResourceType.PRODUCT),
        settings.timeout(),
        settings.cacheTtl(),
        settings.enabled() && settings.hasToken(),
        25);
  }

  public static SourceDescriptor hfProviderOrgs(SourceSettings settings, List<String> orgs) {
    return new SourceDescriptor(
        HF_PROVIDER_ORGS,
        "Hugging Face model makers",
        "HF makers",
        true,
        SourceMode.INDEXED,
        URI.create("https://huggingface.co/models"),
        URI.create("https://huggingface.co/terms-of-service"),
        "Newest models of AI providers' official Hugging Face organisations: "
            + String.join(", ", orgs),
        EnumSet.of(ResourceType.MODEL),
        settings.timeout(),
        settings.cacheTtl(),
        settings.enabled(),
        30);
  }

  public static SourceDescriptor hfTrending(SourceSettings settings) {
    return new SourceDescriptor(
        HF_TRENDING,
        "Hugging Face trending",
        "HF trending",
        true,
        SourceMode.INDEXED,
        URI.create("https://huggingface.co/models?sort=trending"),
        URI.create("https://huggingface.co/terms-of-service"),
        "Model metadata from the Hugging Face Hub API",
        EnumSet.of(ResourceType.MODEL),
        settings.timeout(),
        settings.cacheTtl(),
        settings.enabled(),
        30);
  }

  public static SourceDescriptor mcpRegistry(SourceSettings settings) {
    return new SourceDescriptor(
        MCP_REGISTRY,
        "Official MCP Registry",
        "MCP",
        true,
        SourceMode.INDEXED,
        URI.create("https://registry.modelcontextprotocol.io"),
        URI.create("https://github.com/modelcontextprotocol/registry"),
        "This data comes from the Official MCP Registry (CC0).",
        EnumSet.of(ResourceType.MCP_SERVER),
        settings.timeout(),
        settings.cacheTtl(),
        settings.enabled(),
        25);
  }

  /** The Scout's daily AI news searches. */
  public static SourceDescriptor liveWebScout(SourceSettings settings, List<String> queries) {
    return new SourceDescriptor(
        LIVE_WEB_SCOUT,
        "AI news",
        "News",
        true,
        SourceMode.INDEXED,
        SOURCES_PAGE,
        TERMS_PAGE,
        "Web results found by a live web search; each links to the original publisher.",
        EnumSet.of(ResourceType.NEWS, ResourceType.ARTICLE, ResourceType.EVENT),
        settings.timeout(),
        settings.cacheTtl(),
        settings.enabled() && settings.hasToken() && !queries.isEmpty(),
        48);
  }

  /** [platforms]: the learning platforms the searches are restricted to (any kind of entry). */
  public static SourceDescriptor courses(SourceSettings settings, String query, List<?> platforms) {
    return new SourceDescriptor(
        COURSES,
        "Courses and certifications",
        "Courses",
        true,
        SourceMode.INDEXED,
        SOURCES_PAGE,
        TERMS_PAGE,
        "Course and certification pages found by a live web search; an admin reviews each before it is catalogued.",
        EnumSet.of(ResourceType.COURSE),
        settings.timeout(),
        settings.cacheTtl(),
        settings.enabled() && settings.hasToken() && !platforms.isEmpty() && !query.isEmpty(),
        60);
  }

  /** On only with a model to read the pages ([readerOn]). */
  public static SourceDescriptor liveWebEvents(
      SourceSettings settings, List<String> queries, boolean readerOn) {
    return new SourceDescriptor(
        LIVE_WEB_EVENTS,
        "Upcoming AI events",
        "Events",
        true,
        SourceMode.INDEXED,
        SOURCES_PAGE,
        TERMS_PAGE,
        "Event pages found by a live web search; an AI model reads each page's days, and code"
            + " checks them against the page's own words. Each links to the event's page.",
        EnumSet.of(ResourceType.EVENT),
        settings.timeout(),
        settings.cacheTtl(),
        liveWebEventsOn(settings, queries, readerOn),
        48);
  }

  public static boolean liveWebEventsOn(
      SourceSettings settings, List<String> queries, boolean readerOn) {
    return settings.enabled() && settings.hasToken() && !queries.isEmpty() && readerOn;
  }

  public static SourceDescriptor linkedIn(SourceSettings settings, List<String> queries) {
    return new SourceDescriptor(
        LINKEDIN,
        "LinkedIn live web",
        "LinkedIn",
        true,
        SourceMode.INDEXED,
        URI.create("https://www.linkedin.com"),
        TERMS_PAGE,
        "Public LinkedIn pages found by a live web search; each links to LinkedIn. An admin"
            + " reviews each before it is catalogued.",
        EnumSet.of(
            ResourceType.ARTICLE, ResourceType.NEWS, ResourceType.PRODUCT, ResourceType.EVENT),
        settings.timeout(),
        settings.cacheTtl(),
        settings.enabled() && settings.hasToken() && !queries.isEmpty(),
        60);
  }

  /** One discovery category (ADR 0051): its own source, with its own dot on the Home radar. */
  public static SourceDescriptor discovery(SourceSettings settings, DiscoveryCategory category) {
    boolean enabled = settings.enabled() && category.enabled();
    return new SourceDescriptor(
        category.sourceId(),
        category.name(),
        category.shortName(),
        true,
        SourceMode.INDEXED,
        SOURCES_PAGE,
        TERMS_PAGE,
        category.indexLists()
            ? "Web results found by a live web search; each links to the original publisher."
                + " List articles are added without review; products and articles about them"
                + " are added only after a person checks them."
            : "Web results found by a live web search; each links to the original publisher."
                + " Products and articles about them are added only after a person checks"
                + " them.",
        discoveryTypes(category),
        settings.timeout(),
        settings.cacheTtl(),
        enabled && settings.hasToken(),
        50,
        false,
        true,
        false,
        false,
        false,
        category.family().id());
  }

  private static Set<ResourceType> discoveryTypes(DiscoveryCategory category) {
    return EnumSet.of(category.type(), ResourceType.ARTICLE);
  }
}
