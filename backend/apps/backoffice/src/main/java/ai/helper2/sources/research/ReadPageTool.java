package ai.helper2.sources.research;

import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.research.ResearchTool;
import ai.helper2.scout.PageReader;
import ai.helper2.scout.ReadPage;
import java.net.URI;
import java.util.List;

public final class ReadPageTool implements ResearchTool {

  private final PageReader reader;
  private final DailyBudget reads;

  public ReadPageTool(PageReader reader, DailyBudget reads) {
    this.reader = reader;
    this.reads = reads;
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
    String problem = PublicAddresses.problem(url);
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
    return new Output(page.getRaw(), page.getUrl());
  }
}
