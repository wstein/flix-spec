package flix.spec

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Exercises the second conformance lane on synthesised consumer output.
  *
  * The cases that matter are the ones the first lane cannot express: output that agrees structurally with the reference
  * and is still wrong about its own input, and output that legitimately cannot be evaluated at all.
  */
@RunWith(classOf[JUnitRunner])
class SourceInvariantsTest extends AnyFunSuite with Matchers {

  private val treeInventory = Set("Root", "Decl.Def")
  private val tokenInventory = Set("KeywordDef", "Ident", "ParenL")

  /** Writes one consumer document whose single unit points at a source file written alongside it. */
  private def withOutput(source: String, tree: String)(body: List[String] => Unit): Unit = {
    val dir = Files.createTempDirectory("source-invariants-test")
    try {
      val src = dir.resolve("input.flix")
      Files.writeString(src, source, StandardCharsets.UTF_8)
      val doc = dir.resolve("input.json")
      Files.writeString(
        doc,
        s"""{"units": [{"source": "${src.toString.replace("\\", "\\\\")}", "diagnostics": [], "tree": $tree}]}""",
        StandardCharsets.UTF_8
      )
      body(List(doc.toString))
    } finally deleteRecursively(dir)
  }

  private def deleteRecursively(dir: Path): Unit = {
    Files.list(dir).forEach(Files.deleteIfExists(_))
    Files.deleteIfExists(dir)
  }

  private def verdictOf(lane: SourceInvariants.Lane, id: String): String =
    lane.checks.find(_.id == id).getOrElse(fail(s"no check '$id'")).verdict

  private val WellFormed =
    """{"kind":"Root","children":[
      |  {"token":"KeywordDef","text":"def","start":{"line":1,"col":1},"end":{"line":1,"col":4}},
      |  {"token":"Ident","text":"f","start":{"line":1,"col":5},"end":{"line":1,"col":6}}
      |]}""".stripMargin

  test("output that accounts for its source passes every applicable check") {
    withOutput("def f", WellFormed) { files =>
      val lane = SourceInvariants.run(files, mapped = false, treeInventory, tokenInventory)
      lane.verdict shouldBe "pass"
      lane.checks.map(_.verdict).distinct shouldBe List("pass")
    }
  }

  test("a dropped token is caught even though the tree shape is untouched") {
    // The case the first lane structurally cannot see: kinds, child order and nesting are all
    // identical to the reference, and a token's text is simply gone.
    withOutput("def f", WellFormed.replace("\"text\":\"f\"", "\"text\":\"\"")) { files =>
      val lane = SourceInvariants.run(files, mapped = false, treeInventory, tokenInventory)
      lane.verdict shouldBe "fail"
      verdictOf(lane, "token-accounting") shouldBe "fail"
      verdictOf(lane, "document-shape") shouldBe "pass"
    }
  }

  test("a duplicated token is caught too") {
    withOutput("def f", WellFormed.replace("\"text\":\"def\"", "\"text\":\"defdef\"")) { files =>
      verdictOf(SourceInvariants.run(files, mapped = false, treeInventory, tokenInventory), "token-accounting") shouldBe
        "fail"
    }
  }

  test("whitespace differences never fail the accounting check") {
    withOutput("def    f\n", WellFormed) { files =>
      verdictOf(SourceInvariants.run(files, mapped = false, treeInventory, tokenInventory), "token-accounting") shouldBe
        "pass"
    }
  }

  test("a structural adapter emitting no tokens is not-applicable, never a pass or a fail") {
    // docs/PROJECTION.md leaves tokens uncompared, so emitting none is a permitted choice. Failing
    // it would penalise that choice; passing it would claim a property nothing established.
    withOutput("def f", """{"kind":"Root","children":[{"kind":"Decl.Def","children":[]}]}""") { files =>
      val lane = SourceInvariants.run(files, mapped = false, treeInventory, tokenInventory)
      verdictOf(lane, "token-accounting") shouldBe "not-applicable"
      verdictOf(lane, "token-vocabulary") shouldBe "not-applicable"
      lane.checks.find(_.id == "token-accounting").get.detail should include("no token text")
      // The lane as a whole still passes: shape and kind vocabulary were evaluated.
      lane.verdict shouldBe "pass"
    }
  }

  test("vocabulary checks stand down when a projection map is in play") {
    // With a map the consumer emits its own native names by design. Reporting them here would
    // double-count as defects the very thing the map exists to translate.
    withOutput("def f", WellFormed.replace("\"kind\":\"Root\"", "\"kind\":\"source_file\"")) { files =>
      val mappedLane = SourceInvariants.run(files, mapped = true, treeInventory, tokenInventory)
      verdictOf(mappedLane, "kind-vocabulary") shouldBe "not-applicable"
      mappedLane.verdict shouldBe "pass"

      // Without a map the consumer is claiming canonical output, so the same file is a failure.
      val unmappedLane = SourceInvariants.run(files, mapped = false, treeInventory, tokenInventory)
      verdictOf(unmappedLane, "kind-vocabulary") shouldBe "fail"
    }
  }

  test("a malformed token leaf is a shape failure") {
    withOutput("def f", """{"kind":"Root","children":[{"token":"Ident","text":"deff"}]}""") { files =>
      val lane = SourceInvariants.run(files, mapped = false, treeInventory, tokenInventory)
      verdictOf(lane, "document-shape") shouldBe "fail"
      lane.checks.find(_.id == "document-shape").get.failures.mkString should include("start")
    }
  }

  test("a missing source file fails rather than being skipped") {
    // A check that skips what it cannot read reports success it has not earned.
    val dir = Files.createTempDirectory("source-invariants-test")
    try {
      val doc = dir.resolve("input.json")
      Files.writeString(
        doc,
        s"""{"units": [{"source": "does/not/exist.flix", "diagnostics": [], "tree": $WellFormed}]}""",
        StandardCharsets.UTF_8
      )
      val lane = SourceInvariants.run(List(doc.toString), mapped = false, treeInventory, tokenInventory)
      verdictOf(lane, "token-accounting") shouldBe "fail"
      lane.checks.find(_.id == "token-accounting").get.failures.mkString should include("does not exist")
    } finally deleteRecursively(dir)
  }
}
