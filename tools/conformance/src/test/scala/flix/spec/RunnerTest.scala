package flix.spec

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class RunnerTest extends AnyFunSuite {
  test("the emitted report version matches both the runner declaration and report schema") {
    assert(Runner.supported("conformance-report").asInt == Conformance.ReportSchemaVersion)
    val schema = Json.parseFile(java.nio.file.Paths.get("../../schemas/conformance-report.schema.json"))
    assert(schema("properties")("schemaVersion")("enum").asArray.map(_.asInt) == List(Conformance.ReportSchemaVersion))
  }
  test("supported schemas reject missing, old and future versions") {
    Runner.checkVersion(Json.parse("""{"schemaVersion":2}"""), "projection", "fixture")
    List("{}", """{"schemaVersion":1}""", """{"schemaVersion":3}""").foreach { text =>
      intercept[IllegalArgumentException] { Runner.checkVersion(Json.parse(text), "projection", "fixture") }
    }
  }
}
