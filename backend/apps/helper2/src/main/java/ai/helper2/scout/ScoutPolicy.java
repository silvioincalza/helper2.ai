package ai.helper2.scout;

import java.time.Duration;

/**
 * What the Scout reads from the web and how long it keeps it, as the Privacy page states it (GET
 * /api/v1/policy, ADR 0052).
 *
 * @param pageText the cleaned text of a product's own page, kept to try again
 * @param pageRead a page's address and the fingerprint of its text (ADR 0056)
 * @param productPages how many pages of a product's own website one enrichment reads
 * @param quoteChars the longest quote the evidence gate lets through
 * @param articleExcerptChars the longest excerpt kept of an article found by web search
 * @param productPageExcerptChars the longest excerpt kept of a product page found by web search
 */
public record ScoutPolicy(
    Duration pageText,
    Duration pageRead,
    int productPages,
    int quoteChars,
    int articleExcerptChars,
    int productPageExcerptChars) {}
