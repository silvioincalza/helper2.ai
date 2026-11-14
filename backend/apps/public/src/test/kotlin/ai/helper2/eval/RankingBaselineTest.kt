package ai.helper2.eval

import java.io.File
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.readValue

class RankingBaselineTest {

  @Test
  fun `the baseline was measured with the ranking code as it is now`() {
    val baseline =
      EvalFiles.json.readValue<SearchBaseline>(File(EvalFiles.docs, "search-baseline.json"))

    assertEquals(
      baseline.ranking,
      RankingCode.fingerprint(),
      "One of ${RankingCode.files} changed after docs/eval/search-baseline.json was measured. " +
        "Measure again on the recorded snapshots: ./gradlew :apps:public:evalTest, read " +
        "backend/eval-data/search-report.md, then store the new baseline with " +
        "H2_EVAL_WRITE_BASELINE=true and commit it with the change.",
    )
  }
}
