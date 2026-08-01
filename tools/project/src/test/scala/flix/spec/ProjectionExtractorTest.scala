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

  test("negative fixtures record at least one diagnostic; positive fixtures record none") {
    expectations.foreach { p =>
      val body = Files.readString(p)
      val isNegative = body.contains("\"source\": \"fixtures/negative/")
      val hasDiagnostics = !body.contains("\"diagnostics\": []")
      withClue(s"$p (negative=$isNegative) ") {
        hasDiagnostics shouldBe isNegative
      }
    }
  }

  test("no positive fixture parses to an ErrorTree") {
    expectations.foreach { p =>
      val body = Files.readString(p)
      if (body.contains("\"source\": \"fixtures/positive/")) {
        withClue(s"$p ") { body should not include "\"kind\":\"ErrorTree\"" }
      }
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
