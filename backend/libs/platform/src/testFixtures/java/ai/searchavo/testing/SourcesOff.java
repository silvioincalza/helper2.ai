package ai.searchavo.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@TestPropertySource(
    properties = {
      "searchavo.sources.github.enabled=false",
      "searchavo.sources.huggingface.enabled=false",
      "searchavo.sources.npm.enabled=false",
      "searchavo.sources.hacker-news.enabled=false",
      "searchavo.sources.mcp-registry.enabled=false",
      "searchavo.sources.arxiv.enabled=false",
      "searchavo.sources.live-web.enabled=false",
      "searchavo.sources.hf-trending.enabled=false",
      "searchavo.sources.live-web-scout.enabled=false",
      "searchavo.sources.course-scout.enabled=false",
      "searchavo.sources.linkedin-scout.enabled=false",
      "searchavo.sources.live-web-events-scout.enabled=false",
      "searchavo.sources.discovery.enabled=false",
      "searchavo.sources.github-topic-leaders.enabled=false",
      "searchavo.sources.github-sdks.enabled=false",
      "searchavo.sources.github-awesome.enabled=false",
      "searchavo.sources.hf-provider-orgs.enabled=false",
      "searchavo.sources.ai-deadlines.enabled=false",
      "searchavo.sources.confs-tech.enabled=false",
      "searchavo.sources.news-feeds.enabled=false",
      "searchavo.sources.pypi.enabled=false",
    })
public @interface SourcesOff {}
