package ai.searchavo.site.pages

object LlmsTxt {

  const val SOURCE_CODE = "https://github.com/silvioincalza/helper2.ai"

  fun of(base: String): String =
    """
    |# searchavo.ai
    |
    |> searchavo.ai is a metasearch for the AI ecosystem: one search box across AI apps, models, MCP servers, agents, prompts, agent skills, libraries, datasets, papers, courses, events and news.
    |
    |searchavo.ai searches a disclosed set of sources and its own catalog. Every search shows what happened to each source. A result that several sources found is one row. Dates, popularity and facts that no source states are never filled in: the page says "Not stated".
    |
    |The guide answers a question in plain words. It recommends only resources the search found, each with a quote from that resource's own text, and code checks every quote word for word before the answer is shown. Text written by AI is labelled on the page.
    |
    |searchavo.ai is built and run by Silvio Incalza in Switzerland, on servers in the EU. No account is needed. The source code is public under the Apache-2.0 license.
    |
    |## Main pages
    |
    |- [Search]($base/): one box for the whole AI ecosystem
    |- [Sources]($base/sources): every source searchavo.ai searches, how it reaches you, and whether it answers right now
    |- [AI events]($base/events): conferences, hackathons, meetups, talks and workshops on AI, with the dates their sources state
    |- [Mission]($base/mission): help people find the right AI resource for their need, and show the proof behind every answer
    |- [Privacy]($base/privacy): what searchavo.ai collects, why, for how long, and who receives it
    |- [Terms of use]($base/terms): the terms that apply when you use searchavo.ai
    |
    |## Browse by type
    |
    |${hubs(base)}
    |
    |## Resource pages
    |
    |- Every resource has its own page at $base/resource/{id}/{slug}: its type, license, pricing, dates and sources, as the sources state them.
    |- [Sitemap]($base/sitemap.xml): every resource page worth indexing.
    |
    |## Optional
    |
    |- [Source code]($SOURCE_CODE)
    |- The terms of use do not allow scraping or copying the service in bulk with automated tools.
    |"""
      .trimMargin()

  private fun hubs(base: String): String =
    Hub.entries.joinToString("\n") {
      "- [${it.label}]($base${it.path}): the newest changes in the catalog"
    }
}
