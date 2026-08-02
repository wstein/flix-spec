package flix.spec

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

@RunWith(classOf[JUnitRunner])
class TokenKindExtractorTest extends AnyFunSuite with Matchers {

  private val repoRoot: Path = Paths.get("../..").toAbsolutePath.normalize()
  private val oracleJar: Path = repoRoot.resolve(".oracle/flix.jar")

  test("TokenKind is a flat hierarchy with no qualification needed") {
    // Unlike TreeKind, TokenKind has no sub-traits, so simple names cannot collide. If upstream
    // ever introduces one, this fails and the extractor needs the qualification logic
    // TreeKindNaming already carries.
    val kinds = TokenKindExtractor.extractTokenKinds(oracleJar)
    kinds.map(_.name).foreach(n => withClue(s"$n ") { n should not include "." })
    kinds.map(_.name).distinct.length shouldBe kinds.length
  }

  test("form is decided by MODULE$, not by a hardcoded name") {
    val kinds = TokenKindExtractor.extractTokenKinds(oracleJar)
    kinds.count(_.form == "case-class") shouldBe 1
    kinds.find(_.form == "case-class").map(_.name) shouldBe Some("Err")
    kinds.count(_.form == "case-object") shouldBe kinds.length - 1
  }

  test("pin.json records the count and digest the extractor computes") {
    val kinds = TokenKindExtractor.extractTokenKinds(oracleJar)
    val pin = Files.readString(repoRoot.resolve("pin.json"))

    pin should include(s""""tokenKindCount": ${kinds.length}""")
    pin should include(s""""tokenKindDigest": "${TokenKindExtractor.calculateDigest(kinds)}"""")
  }

  test("ast/tokenkind.json matches the extractor and names its oracle") {
    val kinds = TokenKindExtractor.extractTokenKinds(oracleJar)
    val path = repoRoot.resolve("ast/tokenkind.json")

    Files.exists(path) shouldBe true
    val content = Files.readString(path)
    content should include(s""""tokenKindCount": ${kinds.length}""")
    content should include(s""""tokenKindDigest": "${TokenKindExtractor.calculateDigest(kinds)}"""")
    content should include(s""""oracleSha256": "${TreeKindExtractor.fileDigest(oracleJar)}"""")
    content should include(s""""toolVersion": "${TokenKindExtractor.ToolVersion}"""")
  }

  test("every token emitted by the fixtures exists in the inventory") {
    // The lexical counterpart of the kind check. Before ast/tokenkind.json existed, the projection
    // schema declared `token` as an unconstrained string, so 134 distinct token names were
    // committed and validated against nothing.
    val inventory = """"name": "([^"]+)"""".r
      .findAllMatchIn(Files.readString(repoRoot.resolve("ast/tokenkind.json")))
      .map(_.group(1))
      .toSet

    val seen = Files
      .list(repoRoot.resolve("fixtures/expected"))
      .iterator()
      .asScala
      .filter(_.getFileName.toString.endsWith(".json"))
      .flatMap(p => """"token":"([^"]+)"""".r.findAllMatchIn(Files.readString(p)).map(_.group(1)))
      .toSet

    seen should not be empty
    withClue(s"tokens absent from ast/tokenkind.json: ") { (seen -- inventory) shouldBe empty }
  }
}
