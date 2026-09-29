package flix.spec

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class HtmlReportTest extends AnyFunSuite {
  test("HTML escapes consumer-controlled content and preserves independent verdicts and sample limits") {
    val lane = """{"verdict":"not-applicable","notApplicable":"<script>alert('x')</script>",
      "divergencesListed":1,"divergenceCount":25,"divergences":[{"expected":"<img>","actual":"&"}]}"""
    val lanes = List("oracle_conformance", "recovery_conformance", "diagnostic_conformance", "source_invariants")
      .map(n => s"\"$n\":$lane")
      .mkString(",")
    val doc = Json.parse(s"""{"schemaVersion":7,"consumer":"<script>","provenance":{},"lanes":{$lanes}}""")
    val html = HtmlReport.render(doc)
    assert(!html.contains("<script>"))
    assert(!html.contains("<img>"))
    assert(html.contains("&lt;script&gt;"))
    assert(html.contains("Showing 1 of 25"))
    assert(html.contains("source_invariants: not-applicable"))
    assert(html == HtmlReport.render(doc))
  }

  test("HTML refuses incompatible report versions") {
    intercept[IllegalArgumentException] { HtmlReport.render(Json.parse("""{"schemaVersion":8}""")) }
  }
}
