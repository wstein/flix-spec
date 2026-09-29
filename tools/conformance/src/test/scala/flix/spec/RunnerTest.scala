package flix.spec

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class RunnerTest extends AnyFunSuite {
  test("supported schemas reject missing, old and future versions") {
    Runner.checkVersion(Json.parse("""{"schemaVersion":2}"""), "projection", "fixture")
    List("{}", """{"schemaVersion":1}""", """{"schemaVersion":3}""").foreach { text =>
      intercept[IllegalArgumentException] { Runner.checkVersion(Json.parse(text), "projection", "fixture") }
    }
  }
}
