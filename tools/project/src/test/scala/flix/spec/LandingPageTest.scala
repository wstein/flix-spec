package flix.spec

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import java.nio.file.Files

@RunWith(classOf[JUnitRunner])
class LandingPageTest extends AnyFunSuite with Matchers {

  import LandingPage._

  private val pin = PinSummary(
    tag = "v0.75.1",
    commit = "318bb51a953c58a8785251a80fad8aea005f729f",
    repository = "https://github.com/flix/flix",
    oracleSha256 = "e3177700aead8a22a42c910e73bfb8a326fefdbab4e3eaeaf6d55c328a6bd938",
    attestation = "digest-only"
  )
  private val kinds = KindStats(treeKindCount = 192, tokenKindCount = 160)
  private val coverage = CoverageStats(coveredCount = 186, fixtureCount = 136)
  // Deliberately different from coveredCount: this is the exact scenario that broke the original
  // bash version, where a prose sentence claiming to describe reachability actually read the
  // coverage variable and was only coincidentally correct while the two numbers matched.
  private val reachability = ReachabilityStats(
    reachableCount = 184,
    tokenReachableCount = 153,
    corpusFiles = 873,
    filesParsedWithoutError = 870,
    filesLosslessOfCleanlyParsed = 870
  )
  private val mavenVersions =
    MavenVersions(latest = "0.75.1-SNAPSHOT", release = Some("0.75.1"), all = List("0.75.1", "0.75.1-SNAPSHOT"))
  private def rendered: String = render(pin, kinds, coverage, reachability, mavenVersions)

  test("coverage and reachability are never conflated") {
    // The regression test for the actual bug found: coveredCount (186) must appear where the page
    // claims coverage, and reachableCount (184) where it claims reachability -- never swapped, and
    // never asserted equal, because the two are independent facts from independent generators.
    val html = rendered
    html should include("184/192 TreeKinds")
    html should include("186/192 TreeKinds are exercised by a fixture")
    html should not include "186/192 TreeKinds and"
  }

  test("every declared fact is rendered, nothing declared and silently dropped") {
    val html = rendered
    List(
      pin.tag,
      pin.commit.take(12),
      kinds.treeKindCount.toString,
      kinds.tokenKindCount.toString,
      coverage.coveredCount.toString,
      coverage.fixtureCount.toString,
      reachability.reachableCount.toString,
      reachability.tokenReachableCount.toString,
      reachability.corpusFiles.toString,
      reachability.filesLosslessOfCleanlyParsed.toString
    ).foreach(fact => withClue(s"missing '$fact' ") { html should include(fact) })
  }

  test("the page claims no dependents it cannot substantiate") {
    // The Dependents table was populated by listing ast/projection/, which no longer exists --
    // projection maps are the consumers' own data now. A section sourced from a directory that
    // cannot exist would have rendered "none yet" in perpetuity, stating the opposite of the truth.
    val html = rendered
    html should not include "Dependents"
    html should not include "none yet"
    html should not include "ast/projection"
  }

  test("both maven versions are listed with distinct badges") {
    val html = rendered
    html should include(">0.75.1<")
    html should include("badge-release")
    html should include(">0.75.1-SNAPSHOT<")
    html should include("badge-snapshot")
  }

  test("release version is preferred in the usage snippet; falls back to latest") {
    rendered should include("""implementation("io.github.wstein:flix-spec:0.75.1")""")

    val noRelease = mavenVersions.copy(release = None)
    val html = render(pin, kinds, coverage, reachability, noRelease)
    html should include("""implementation("io.github.wstein:flix-spec:0.75.1-SNAPSHOT")""")
  }

  test("HTML is well-formed: every opened tag closes, in order, none left open") {
    val html = rendered
    val voidElements = Set("meta", "link", "br", "img", "hr", "input", "!doctype")
    val tagPattern = """<(/?)([a-zA-Z][a-zA-Z0-9]*)[^>]*>""".r

    var stack = List.empty[String]
    val mismatches = scala.collection.mutable.ListBuffer.empty[String]

    tagPattern.findAllMatchIn(html).foreach { m =>
      val closing = m.group(1) == "/"
      val tag = m.group(2).toLowerCase
      if (!voidElements.contains(tag) && !m.group(0).endsWith("/>")) {
        if (!closing) stack = tag :: stack
        else
          stack match {
            case head :: tail if head == tag => stack = tail
            case _                           => mismatches += s"unexpected </$tag>, stack was $stack"
          }
      }
    }

    withClue(mismatches.mkString("; ")) { mismatches shouldBe empty }
    withClue(s"unclosed at end: $stack ") { stack shouldBe empty }
  }

  test("dynamic values are HTML-escaped") {
    val dirty = pin.copy(attestation = """<script>alert("x")</script> & "quoted"""")
    val html = render(dirty, kinds, coverage, reachability, mavenVersions)
    html should not include "<script>"
    html should include("&lt;script&gt;")
  }

  test("parseMavenMetadata reads a real generated file") {
    // A minimal, real maven-metadata.xml built on the fly rather than depending on a network
    // fetch or a prior publish having run in this test environment.
    val tmp = Files.createTempFile("maven-metadata", ".xml")
    Files.writeString(
      tmp,
      """<?xml version="1.0" encoding="UTF-8"?>
        |<metadata>
        |  <groupId>io.github.wstein</groupId>
        |  <artifactId>flix-spec</artifactId>
        |  <versioning>
        |    <latest>0.75.1-SNAPSHOT</latest>
        |    <release>0.75.1</release>
        |    <versions>
        |      <version>0.75.1-SNAPSHOT</version>
        |      <version>0.75.1</version>
        |    </versions>
        |  </versioning>
        |</metadata>
        |""".stripMargin
    )
    val mv = parseMavenMetadata(tmp)
    mv.release shouldBe Some("0.75.1")
    mv.latest shouldBe "0.75.1-SNAPSHOT"
    mv.all should contain theSameElementsAs List("0.75.1", "0.75.1-SNAPSHOT")
    Files.delete(tmp)
  }
}
