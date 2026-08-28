package flix.spec

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** Refuses to build a version this repository has already published once.
  *
  * `pages.yml` has a `Refuse to Republish an Existing Release Version` step, and it tests the gh-pages tree — which is
  * the accumulator of what is published *now*, not a history of what has ever been published. Those differ here: an
  * earlier numbering scheme released `0.75.1` through `0.75.7`, and the gh-pages branch that recorded them was later
  * rebuilt. The gate would therefore wave through a republish of precisely the coordinates most likely to be sitting in
  * someone's dependency cache, resolved in preference to the network because a release is assumed immutable.
  *
  * That is not a thought experiment. Adopting `0.75.2` in `flix-jetbrains-plugin` resolved a different artifact of the
  * same name out of the local Gradle cache and reported 1/136 fixtures agreeing with 669 divergences — a number
  * indistinguishable from a catastrophic grammar regression, and nothing of the kind.
  *
  * So the retirement list lives in the repository, where it survives a branch rebuild, and it is asserted here where CI
  * runs it on every push rather than only at the moment of publishing. A tag is a bad time to discover this.
  */
@RunWith(classOf[JUnitRunner])
class VersionTest extends AnyFunSuite with Matchers {

  private val repoRoot: Path = Paths.get("../..").toAbsolutePath.normalize()

  private def declaredVersion: String = {
    val text = Files.readString(repoRoot.resolve("gradle.properties"), StandardCharsets.UTF_8)
    """(?m)^version=(.+)$""".r.findFirstMatchIn(text).map(_.group(1).trim).getOrElse("")
  }

  private def retired: List[String] =
    Json.parseFile(repoRoot.resolve("retired-versions.json"))("retired").asArray.map(_("version").asString)

  test("the declared version is not a retired coordinate") {
    val v = declaredVersion
    withClue("gradle.properties has no version= line: ")(v should not be empty)
    withClue(s"version $v was published once already; pick the next free coordinate: ")(retired should not contain v)
  }

  test("the version follows the published scheme") {
    // major.minor track upstream, patch is this repository's own counter. Nothing else orders
    // correctly in every ecosystem, and a decorated version was the previous mistake.
    declaredVersion should fullyMatch regex """\d+\.\d+\.\d+"""
  }

  test("every retired coordinate carries a reason") {
    val entries = Json.parseFile(repoRoot.resolve("retired-versions.json"))("retired").asArray
    withClue("the list must not be empty while the renumbering is in this repository's history: ") {
      entries should not be empty
    }
    entries.foreach { e =>
      withClue(s"${e("version").asString}: ") {
        e("version").asString should fullyMatch regex """\d+\.\d+\.\d+"""
        e("reason").asString.length should be > 20
      }
    }
  }

  test("retired coordinates are sorted and unique") {
    val v = retired
    v shouldBe v.distinct
    v shouldBe v.sortBy { s =>
      val p = s.split('.').map(_.toInt); (p(0), p(1), p(2))
    }
  }
}
