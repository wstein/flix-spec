package flix.spec

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import java.nio.file.{Files, Path, Paths}

/** Guards the generated Markdown blocks.
  *
  * `verify.sh` regenerates them and CI diffs the result, which catches drift on any run that reaches that far. These
  * tests fail earlier and say why: a missing or duplicated marker is a broken generator contract rather than a stale
  * number, and the two deserve different messages.
  */
@RunWith(classOf[JUnitRunner])
class DocMetricsTest extends AnyFunSuite with Matchers {

  private val repoRoot: Path = Paths.get("../..").toAbsolutePath.normalize()

  private def read(rel: String): String = Files.readString(repoRoot.resolve(rel))

  /** The body between the markers for `name`, excluding the markers themselves. */
  private def block(doc: String, name: String): String = {
    val begin = s"<!-- generated: $name -->"
    val end = s"<!-- /generated: $name -->"
    val b = doc.indexOf(begin)
    val e = doc.indexOf(end)
    withClue(s"'$name' block markers: ") {
      b should be >= 0
      e should be > b
    }
    doc.substring(b + begin.length, e)
  }

  private val Blocks =
    List("README.md" -> "status", "docs/CONFORMANCE.md" -> "wrappers", "docs/CONFORMANCE.md" -> "lossless")

  test("every generated block is present exactly once and well formed") {
    // A second opening marker would make splice() rewrite the first and silently orphan the rest of
    // the document between them, so this is a correctness check on the generator's contract, not
    // tidiness.
    Blocks.foreach { case (file, name) =>
      val doc = read(file)
      withClue(s"$file [$name]: ") {
        doc.sliding(s"<!-- generated: $name -->".length).count(_ == s"<!-- generated: $name -->") shouldBe 1
        doc.sliding(s"<!-- /generated: $name -->".length).count(_ == s"<!-- /generated: $name -->") shouldBe 1
        block(doc, name).trim should not be empty
      }
    }
  }

  test("the status block agrees with ast/status.json") {
    val status = Json.parseFile(repoRoot.resolve("ast/status.json"))
    val body = block(read("README.md"), "status")

    // Read the table back rather than re-rendering it: re-rendering would compare the generator
    // against itself and pass on any committed output at all.
    def cell(label: String): Int = {
      val row = body.linesIterator.find(_.startsWith(s"| $label ")).getOrElse(fail(s"no '$label' row in the block"))
      row.split('|').map(_.trim).filter(_.nonEmpty).last.toInt
    }

    cell("Inventory") shouldBe status("tokenKind")("total").asInt
    List("reachable-covered", "fixture-only", "corpus-only", "structurally-unattachable", "unknown").foreach { s =>
      withClue(s"'$s' TokenKind column: ")(cell(s"`$s`") shouldBe status("tokenKind")(s).asInt)
    }

    withClue("the block must name the pin it was measured at: ") {
      body should include(status("upstreamCommit").asString.take(8))
    }
    withClue("the block must state the corpus size it was measured over: ") {
      body should include(s"${status("corpusFiles").asInt} corpus files")
    }
  }

  test("the wrapper and lossless blocks agree with the artifacts") {
    val coverage = Json.parseFile(repoRoot.resolve("ast/coverage.json"))
    val reach = Json.parseFile(repoRoot.resolve("ast/reachability.json"))
    val doc = read("docs/CONFORMANCE.md")

    val wrappers = block(doc, "wrappers")
    wrappers should include(s"${coverage("singleChildWrapperNodes").asInt} of ${coverage("nodeCount").asInt} nodes")
    wrappers should include(s"${coverage("fixtureCount").asInt} fixtures")

    val lossless = block(doc, "lossless")
    lossless should include(s"${coverage("fixtureCount").asInt} fixtures")
    lossless should include(reach("filesParsedWithoutError").asInt.toString)
    lossless should include(s"${reach("corpusFiles").asInt} corpus files")
  }

  test("no generated count is also retyped as prose") {
    // The specific regressions this whole mechanism exists to prevent. Each of these was true of a
    // committed README or CONFORMANCE.md at some point, and each read as authoritative.
    val readme = read("README.md")
    val conformance = read("docs/CONFORMANCE.md")

    withClue("a hand-written fixture count outside a generated block: ") {
      readme should not include "134 fixtures"
    }
    withClue("a hand-written coverage ratio that the status table now owns: ") {
      readme should not include "184 of 192"
    }
    withClue("coverage no longer equals reachability -- fixtures reach kinds the corpus does not: ") {
      readme should not include "Coverage equals reachability"
    }
    withClue("a hand-written wrapper-node count: ") {
      conformance should not include "476 of 4181"
    }
  }
}
