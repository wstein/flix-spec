package flix.spec

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

@RunWith(classOf[JUnitRunner])
class ProjectionExtractorTest extends AnyFunSuite with Matchers {

  private val repoRoot: Path = Paths.get("../..").toAbsolutePath.normalize()
  private val expectedDir: Path = repoRoot.resolve("fixtures/expected")

  private def expectations: List[Path] =
    Files
      .list(expectedDir)
      .iterator()
      .asScala
      .filter(_.getFileName.toString.endsWith(".json"))
      .toList
      .sortBy(_.toString)

  private def fixtures: List[Path] =
    List("positive", "negative").flatMap { sub =>
      Files
        .list(repoRoot.resolve(s"fixtures/$sub"))
        .iterator()
        .asScala
        .filter(_.getFileName.toString.endsWith(".flix"))
        .toList
    }

  test("every fixture has a committed expectation") {
    val names = fixtures.map(_.getFileName.toString.stripSuffix(".flix")).sorted
    val have = expectations.map(_.getFileName.toString.stripSuffix(".json")).sorted

    names should not be empty
    have shouldBe names
  }

  test("kind names are sub-trait qualified, never bare") {
    // Bare names are ambiguous: 13 simple names are reused across sub-traits, so a projection
    // emitting "Apply" cannot distinguish Expr.Apply from Type.Apply.
    val inventory = Files.readString(repoRoot.resolve("ast/treekind.json"))
    val qualified = """"name": "([A-Za-z]+\.[A-Za-z]+)"""".r
      .findAllMatchIn(inventory)
      .map(_.group(1))
      .toSet

    val body = Files.readString(expectedDir.resolve("hello.json"))
    val emitted = """"kind":"([^"]+)"""".r.findAllMatchIn(body).map(_.group(1)).toSet

    emitted.exists(_.contains(".")) shouldBe true
    emitted.filter(_.contains(".")).foreach(k => qualified should contain(k))
  }

  test("sources are repository-relative and diagnostics carry no absolute paths") {
    // An absolute path would make the committed expectation machine-specific and fail the diff
    // gate on any other checkout.
    expectations.foreach { p =>
      val body = Files.readString(p)
      withClue(s"$p ") {
        """"source": "(/|[A-Za-z]:)""".r.findFirstIn(body) shouldBe None
        body should not include repoRoot.toString
      }
    }
  }

  /** Kinds the parser emits only for malformed input. */
  private val ErrorKinds = List("ErrorTree", "OperatorError", "TrailingDot", "UnclosedMark")

  test("negative fixtures show malformation; positive fixtures show none") {
    // Evidence is a diagnostic *or* an error kind, not a diagnostic alone. Parser2 recovers
    // silently from some malformed input and defers the error to a later phase: a block
    // containing `1 2` yields a synthetic OperatorError node and no parser diagnostic at all,
    // because Weeder2 is what turns it into MissingBinaryOperator. A consumer cannot be required
    // to report a diagnostic where the reference itself reports none.
    expectations.foreach { p =>
      val body = Files.readString(p)
      val isNegative = body.contains("\"source\": \"fixtures/negative/")
      val hasDiagnostics = !body.contains("\"diagnostics\": []")
      val hasErrorKind = ErrorKinds.exists(k => body.contains(s""""kind":"$k""""))
      withClue(s"$p (negative=$isNegative) ") {
        (hasDiagnostics || hasErrorKind) shouldBe isNegative
      }
    }
  }

  test("no positive fixture parses to an error kind") {
    expectations.foreach { p =>
      val body = Files.readString(p)
      if (body.contains("\"source\": \"fixtures/positive/")) {
        ErrorKinds.foreach(k => withClue(s"$p ") { body should not include s""""kind":"$k"""" })
      }
    }
  }

  test("fixtures cover every kind the corpus proves reachable") {
    // The two artifacts are generated independently -- coverage from fixtures, reachability from
    // the 873-file corpus -- so agreement is a real check, not a tautology. A kind that the
    // reference emits somewhere in the corpus but no fixture exercises is a genuine gap.
    //
    // This used to regex-scrape from the "uncovered" key to end-of-file, which silently assumed
    // "uncovered" was the last member of coverage.json. Adding the tokenKind members after it swept
    // every token name into the set. Parse the JSON instead -- this module ships a reader.
    val coverage = Json.parseFile(repoRoot.resolve("ast/coverage.json"))
    val reachability = repoRoot.resolve("ast/reachability.json")

    if (Files.exists(reachability)) {
      val uncovered = coverage("uncovered").asArray.map(_.asString).toSet
      val unreachable = Json.parseFile(reachability)("unreachable").asArray.map(_.asString).toSet

      withClue("kinds reachable in the corpus but not covered by any fixture: ") {
        (uncovered -- unreachable) shouldBe empty
      }
    }
  }

  test("coverage measures the lexical vocabulary as well as the tree vocabulary") {
    // ast/tokenkind.json is the whole contract for consumers with no parse tree, so "which tokens
    // does the suite exercise" has to be measured rather than asserted in prose.
    val coverage = Json.parseFile(repoRoot.resolve("ast/coverage.json"))
    val inventory = Json.parseFile(repoRoot.resolve("ast/tokenkind.json"))("kinds").asArray.map(_("name").asString)

    val covered = coverage("tokenCovered").asObject
    val uncovered = coverage("tokenUncovered").asArray.map(_.asString)

    coverage("tokenKindCount").asInt shouldBe inventory.length
    coverage("tokenCoveredCount").asInt shouldBe covered.size
    coverage("tokenUncoveredCount").asInt shouldBe uncovered.length
    (covered.size + uncovered.length) shouldBe inventory.length

    withClue("tokens counted but absent from ast/tokenkind.json: ") {
      (covered.keySet ++ uncovered.toSet) -- inventory.toSet shouldBe empty
    }
    withClue("a covered token must have a non-zero occurrence count: ") {
      covered.filter(_._2.asInt <= 0).keys shouldBe empty
    }
  }

  test("coverage artifact agrees with the inventory") {
    val coverage = Files.readString(repoRoot.resolve("ast/coverage.json"))
    val inventory = Files.readString(repoRoot.resolve("ast/treekind.json"))
    val count = """"treeKindCount": (\d+)""".r.findFirstMatchIn(inventory).get.group(1)

    coverage should include(s""""treeKindCount": $count""")
    coverage should include(""""coveredCount"""")
    coverage should include(""""uncovered"""")
  }
}
