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
  private val expectedDir: Path = repoRoot.resolve(ProjectionExtractor.NormalizedDir)

  private def documents(dir: Path): List[Path] =
    Files
      .list(dir)
      .iterator()
      .asScala
      .filter(_.getFileName.toString.endsWith(".json"))
      .toList
      .sortBy(_.toString)

  private def expectations: List[Path] = documents(expectedDir)

  /** The reference's own trees. Every claim about what the *parser* did -- which error kinds it produced, whether a
    * fixture is malformed at all -- has to be read here: normalisation splices the error vocabulary out of
    * `fixtures/expected` on purpose, so asking that form about error recovery would get a confidently wrong answer.
    */
  private def rawTrees: List[Path] = documents(repoRoot.resolve(ProjectionExtractor.RawDir))

  private def fixtures: List[Path] =
    List("positive", "negative").flatMap { sub =>
      Files
        .list(repoRoot.resolve(s"fixtures/$sub"))
        .iterator()
        .asScala
        .filter(_.getFileName.toString.endsWith(".flix"))
        .toList
    }

  test("every fixture has a committed expectation in both forms") {
    val names = fixtures.map(_.getFileName.toString.stripSuffix(".flix")).sorted

    names should not be empty
    expectations.map(_.getFileName.toString.stripSuffix(".json")).sorted shouldBe names
    rawTrees.map(_.getFileName.toString.stripSuffix(".json")).sorted shouldBe names
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
    // gate on any other checkout. Both forms are committed, so both are gated.
    (expectations ++ rawTrees).foreach { p =>
      val body = Files.readString(p)
      withClue(s"$p ") {
        """"source": "(/|[A-Za-z]:)""".r.findFirstIn(body) shouldBe None
        body should not include repoRoot.toString
      }
    }
  }

  /** Kinds the parser emits only for malformed input. */
  private val ErrorKinds = List("ErrorTree", "OperatorError", "TrailingDot", "UnclosedMark")

  test("negative fixtures show malformation in the raw tree; positive fixtures show none") {
    // Evidence is a diagnostic *or* an error kind, not a diagnostic alone. Parser2 recovers
    // silently from some malformed input and defers the error to a later phase: a block
    // containing `1 2` yields a synthetic OperatorError node and no parser diagnostic at all,
    // because Weeder2 is what turns it into MissingBinaryOperator. A consumer cannot be required
    // to report a diagnostic where the reference itself reports none.
    rawTrees.foreach { p =>
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
    rawTrees.foreach { p =>
      val body = Files.readString(p)
      if (body.contains("\"source\": \"fixtures/positive/")) {
        ErrorKinds.foreach(k => withClue(s"$p ") { body should not include s""""kind":"$k"""" })
      }
    }
  }

  test("the normalized form keeps every diagnostic the raw form recorded") {
    // Normalisation removes the error *vocabulary* from the tree, never the diagnostics. Losing those would make the
    // negative fixtures indistinguishable from the positive ones in the form consumers actually read, and the
    // diagnostic contract (kind and line gated, col and message advisory) applies to both.
    rawTrees.zip(expectations).foreach { case (r, e) =>
      val raw = Json.parseFile(r)("units").asArray.map(_("diagnostics").asArray.length)
      val normalized = Json.parseFile(e)("units").asArray.map(_("diagnostics").asArray.length)
      withClue(s"${r.getFileName}: ")(normalized shouldBe raw)
    }
  }

  test("fixtures cover every kind the corpus proves reachable") {
    // The two artifacts are generated independently -- coverage from fixtures, reachability from
    // the 874-file corpus -- so agreement is a real check, not a tautology. A kind that the
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

  test("every kind carries an evidence-backed status") {
    // ast/status.json states the property above positively, and adds the one neither coverage nor
    // reachability can express alone: a kind that is neither exercised, nor corpus-reachable, nor
    // argued unattachable is `unknown` -- the status that must never silently accumulate.
    val statusPath = repoRoot.resolve("ast/status.json")
    if (Files.exists(statusPath)) {
      val status = Json.parseFile(statusPath)
      val treeStatus = status("treeKindStatus").asObject
      val tokenStatus = status("tokenKindStatus").asObject

      withClue("TreeKinds the corpus reaches but no fixture pins: ") {
        treeStatus.filter(_._2.asString == "corpus-only").keys shouldBe empty
      }
      withClue("TokenKinds the corpus reaches but no fixture pins: ") {
        tokenStatus.filter(_._2.asString == "corpus-only").keys shouldBe empty
      }

      // Every inventory entry must be classified, or the join silently dropped one.
      treeStatus.size shouldBe status("treeKind")("total").asInt
      tokenStatus.size shouldBe status("tokenKind")("total").asInt

      // The per-kind map and the tallies are written from the same data, so a disagreement means
      // the writer, not the classification, is wrong.
      List("reachable-covered", "fixture-only", "corpus-only", "structurally-unattachable", "unknown").foreach { s =>
        withClue(s"treeKind tally for '$s': ")(
          status("treeKind")(s).asInt shouldBe treeStatus.count(_._2.asString == s)
        )
        withClue(s"tokenKind tally for '$s': ")(
          status("tokenKind")(s).asInt shouldBe tokenStatus.count(_._2.asString == s)
        )
      }
    }
  }

  test("the four vocabulary roles partition the TreeKind inventory") {
    // A partition, not a ranking. The four sources are disjoint by construction -- Transparency refuses an entry that
    // is also claimed unattachable, and a contract entry carries exactly one rule -- so every kind gets exactly one
    // role and no kind falls through. Asserting it here is what keeps that argument from quietly becoming false.
    val status = Json.parseFile(repoRoot.resolve("ast/status.json"))
    val roles = status("treeKindRole").asObject
    val inventory =
      Json.parseFile(repoRoot.resolve("ast/treekind.json"))("kinds").asArray.map(_("name").asString).toSet

    roles.keySet shouldBe inventory
    withClue("a role outside the four: ") {
      roles.values.map(_.asString).toSet.diff(Set("syntax", "wrapper", "error-marker", "unattachable")) shouldBe empty
    }

    val tally = status("treeKindRoleTally")
    tally("total").asInt shouldBe inventory.size
    List("syntax", "wrapper", "error-marker", "unattachable").foreach { r =>
      withClue(s"tally for '$r': ")(tally(r).asInt shouldBe roles.count(_._2.asString == r))
    }
    List("syntax", "wrapper", "error-marker", "unattachable").map(tally(_).asInt).sum shouldBe inventory.size
  }

  test("each role names exactly the kinds its source names") {
    // The roles are derived, and a derivation nobody checks is a second, silent copy of its inputs.
    val roles = Json.parseFile(repoRoot.resolve("ast/status.json"))("treeKindRole").asObject
    val contract = Transparency.parse(Json.parseFile(repoRoot.resolve("ast/transparency.json")))
    val unattachable =
      Json.parseFile(repoRoot.resolve("ast/unattachable.json"))("treeKinds").asArray.map(_("name").asString).toSet

    def named(role: String): Set[String] = roles.filter(_._2.asString == role).keySet

    named("wrapper") shouldBe contract.elide
    named("error-marker") shouldBe contract.recoveryMarkers
    named("unattachable") shouldBe unattachable
  }

  test("structural-unattachability evidence is argued, not asserted") {
    // The evidence file is the only hand-maintained input to the status join, so it is the only
    // place a wrong claim can enter. Measurement must be able to refute it.
    val evidence = Json.parseFile(repoRoot.resolve("ast/unattachable.json"))
    val coverage = Json.parseFile(repoRoot.resolve("ast/coverage.json"))
    val pin = Json.parseFile(repoRoot.resolve("pin.json"))

    withClue("citations were read at a different commit than pin.json names: ") {
      evidence("upstreamCommit").asString shouldBe pin("upstream")("commit").asString
    }

    val treeEntries = evidence("treeKinds").asArray
    val tokenEntries = evidence("tokenKinds").asArray

    List("treeKinds" -> treeEntries, "tokenKinds" -> tokenEntries).foreach { case (field, entries) =>
      val names = entries.map(_("name").asString)
      withClue(s"$field must be sorted by name: ")(names shouldBe names.sorted)
      withClue(s"$field must not repeat a name: ")(names.distinct.length shouldBe names.length)
      withClue(s"$field entries must argue, not assert: ") {
        entries.filter(_("reason").asString.length < 40) shouldBe empty
      }
    }

    // A kind a fixture actually contains cannot also be unattachable. KindStatus fails the build on
    // this; asserting it here too keeps the contradiction visible in the test report.
    val covered = coverage("covered").asObject.keySet
    withClue("claimed unattachable, yet a fixture contains it: ") {
      treeEntries.map(_("name").asString).filter(covered) shouldBe empty
    }
    val tokensCovered = coverage("tokenCovered").asObject.keySet
    withClue("claimed unattachable, yet a fixture contains it: ") {
      tokenEntries.map(_("name").asString).filter(tokensCovered) shouldBe empty
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
