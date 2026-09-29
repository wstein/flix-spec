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
    // Read from the runner's own table rather than hardcoded: this test broke on the version-8 bump, which
    // is the wrong thing to have to remember when the number moves for a reason unrelated to HTML.
    val supported = Runner.supportedVersion("conformance-report")
    val doc =
      Json.parse(s"""{"schemaVersion":$supported,"consumer":"<script>","provenance":{},"lanes":{$lanes}}""")
    val html = HtmlReport.render(doc)
    assert(!html.contains("<script>"))
    assert(!html.contains("<img>"))
    assert(html.contains("&lt;script&gt;"))
    assert(html.contains("Showing 1 of 25"))
    assert(html.contains("source_invariants: not-applicable"))
    assert(html == HtmlReport.render(doc))
  }

  test("HTML refuses incompatible report versions") {
    // Both neighbours of the supported version, derived. Naming a literal here meant the test asserted
    // that version 8 was incompatible right up until version 8 became the supported one, at which point it
    // failed for a reason that had nothing to do with HTML.
    val supported = Runner.supportedVersion("conformance-report")
    intercept[IllegalArgumentException] {
      HtmlReport.render(Json.parse(s"""{"schemaVersion":${supported + 1}}"""))
    }
    intercept[IllegalArgumentException] {
      HtmlReport.render(Json.parse(s"""{"schemaVersion":${supported - 1}}"""))
    }
  }
}
