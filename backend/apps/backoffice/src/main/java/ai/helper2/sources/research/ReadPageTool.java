package ai.helper2.sources.research;

import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.research.ResearchTool;
import ai.helper2.scout.PageReader;
import ai.helper2.scout.ReadPage;
import ai.helper2.search.ShadowLibraries;
import java.net.URI;
import java.util.List;

public final class ReadPageTool implements ResearchTool {

  private final PageReader reader;
  private final DailyBudget reads;
  private final PublicAddresses addresses;
  private final ShadowLibraries shadowLibraries;

  public ReadPageTool(PageReader reader, DailyBudget reads, ShadowLibraries shadowLibraries) {
    this.reader = reader;
    this.reads = reads;
    this.shadowLibraries = shadowLibraries;
    this.addresses = new PublicAddresses(shadowLibraries);
  }

  @Override
  public String name() {
    return "read_page";
  }

  @Override
  public String description() {
    return "Read a public web page as text. The text is evidence you can quote; cite it by the evidenceId the result"
        + " gives.";
  }

  @Override
  public String parameters() {
    return """
    {"type":"object","properties":{"url":{"type":"string","description":"An https URL"}},"required":["url"]}\
    """;
  }

  @Override
  public Output call(String arguments) {
    String url = Args.of(arguments).text("url");
    if (url == null) {
      return Output.error("url is required");
    }
    String problem = addresses.problem(url);
    if (problem != null) {
      return Output.error("not read: " + problem);
    }
    if (!reader.getAvailable()) {
      return Output.error("the page reader is not configured");
    }
    if (!reads.tryAcquire()) {
      return Output.error("today's page reads for research are used up");
    }
    List<ReadPage> pages = reader.extract(List.of(URI.create(url)));
    if (pages.isEmpty() || pages.getFirst().getRaw().isBlank()) {
      return new Output("the page has no readable text", null);
    }
    ReadPage page = pages.getFirst();
    if (!endsOnAKeptAddress(page)) {
      return Output.error("not read: the page ends on a shadow library or an address with no host");
    }
    return new Output(page.getRaw(), page.getUrl());
  }

  private boolean endsOnAKeptAddress(ReadPage page) {
    try {
      String host = URI.create(page.getUrl()).getHost();
      return host != null && !shadowLibraries.isShadowLibrary(host);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
