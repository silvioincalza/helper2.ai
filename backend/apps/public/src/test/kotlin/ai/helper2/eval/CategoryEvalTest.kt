package ai.helper2.eval

import ai.helper2.platform.config.CategoriesProperties
import ai.helper2.shared.CategoryMatcher
import ai.helper2.testing.ConfigBinding
import java.io.File
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * The category matcher against the labels in `src/test/resources/eval/categories-labelled.csv` (ADR
 * 0013): `./gradlew evalTest --tests ai.helper2.eval.CategoryEvalTest`. Prints precision and recall
 * per category and writes them to build/eval/categories.md. Skipped while the file has no labelled
 * rows.
 */
@Tag("eval")
class CategoryEvalTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(CategoriesProperties::class)
  class Binding

  @Test
  fun `prints precision and recall per category`() {
    val rows =
      CategoryEvaluation.read(
        File(javaClass.getResource("/eval/categories-labelled.csv")!!.toURI())
      )
    assumeTrue(rows.isNotEmpty(), "categories-labelled.csv has no labelled rows yet.")
    var report = ""
    ConfigBinding.of(Binding::class.java).run { c ->
      val matcher = CategoryMatcher(c.getBean(CategoriesProperties::class.java).catalog())
      report = CategoryEvaluation(matcher, rows).report()
    }
    println(report)
    val out = File("build/eval/categories.md")
    out.parentFile.mkdirs()
    out.writeText(report)
    assertTrue(out.isFile)
  }
}
