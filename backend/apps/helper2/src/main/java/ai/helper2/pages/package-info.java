/**
 * Pages for crawlers (ADR 0050): what the server writes into the app shell for each address of the
 * app, real 404s and 301s, sitemap.xml and robots.txt. Domain (Kotlin, framework-free) at the top
 * level: the route list, the head of each page and the index-worthy rule. The use case in usecase,
 * the template and its HTML in internal, the routes and the crawler files in web.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Pages for crawlers")
package ai.helper2.pages;
