package flix.spec

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

@RunWith(classOf[JUnitRunner])
class ProjectionMapTest extends AnyFunSuite with Matchers {

  private val repoRoot: Path = Paths.get("../..").toAbsolutePath.normalize()
  private val mapDir: Path = repoRoot.resolve("ast/projection")

  private def maps: List[Path] =
    if (!Files.isDirectory(mapDir)) Nil
    else
      Files
        .list(mapDir)
        .iterator()
        .asScala
        .filter(_.getFileName.toString.endsWith(".json"))
        .toList
        .sortBy(_.toString)

  private def inventory: Set[String] =
    """"name": "([^"]+)"""".r
      .findAllMatchIn(Files.readString(repoRoot.resolve("ast/treekind.json")))
      .map(_.group(1))
      .toSet

  /** Values of the `mappings` object, and entries of `elide` -- both name canonical kinds. */
  private def canonicalRefs(body: String): Set[String] = {
    val mappings = """"[^"]+"\s*:\s*"([^"]+)"""".r
      .findAllMatchIn(body.substring(body.indexOf("\"mappings\"")).takeWhile(_ != ']'))
      .map(_.group(1))
      .toSet
    val elideBlock =
      if (body.contains("\"elide\"")) body.substring(body.indexOf("\"elide\""))
      else ""
    val elide = """"([A-Za-z][A-Za-z.]*)"""".r
      .findAllMatchIn(elideBlock.takeWhile(_ != ']'))
      .map(_.group(1))
      .toSet - "elide"
    mappings ++ elide
  }

  test("every projection map targets kinds that exist in the inventory") {
    // A stale or misspelled kind name would silently never match, which reads as agreement --
    // strictly worse than an unmapped node, which is reported as a gap.
    val known = inventory
    maps.foreach { p =>
      val refs = canonicalRefs(Files.readString(p))
      withClue(s"$p ") { (refs -- known) shouldBe empty }
    }
  }

  test("no node is both mapped and ignored") {
    maps.foreach { p =>
      val body = Files.readString(p)
      val mapped = """"([^"]+)"\s*:\s*"[^"]+"""".r
        .findAllMatchIn(body.substring(body.indexOf("\"mappings\"")).takeWhile(_ != '}'))
        .map(_.group(1))
        .toSet - "mappings"
      val ignoredBlock =
        if (body.contains("\"ignored\"")) body.substring(body.indexOf("\"ignored\""))
        else ""
      val ignored = """"([a-z_]+)"""".r
        .findAllMatchIn(ignoredBlock.takeWhile(_ != ']'))
        .map(_.group(1))
        .toSet - "ignored"

      withClue(s"$p ") { (mapped intersect ignored) shouldBe empty }
    }
  }

  test("projection maps declare a consumer and a schema version") {
    maps.foreach { p =>
      val body = Files.readString(p)
      withClue(s"$p ") {
        body should include("\"schemaVersion\"")
        body should include("\"consumer\"")
      }
    }
  }
}
