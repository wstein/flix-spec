package flix.spec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner
import org.junit.runner.RunWith

import java.nio.file.{Files, Paths}

@RunWith(classOf[JUnitRunner])
class TreeKindExtractorTest extends AnyFunSuite with Matchers {

  test("extractTreeKinds should return exactly 192 unique qualified kinds") {
    val kinds = TreeKindExtractor.extractTreeKinds()

    kinds.length shouldBe 192
    kinds.map(_.name).distinct.length shouldBe 192

    // Check specific known kinds and their qualification
    kinds.map(_.name) should contain("Decl.Def")
    kinds.map(_.name) should contain("Expr.Apply")
    kinds.map(_.name) should contain("Type.Apply")
    kinds.map(_.name) should contain("Expr.Tuple")
    kinds.map(_.name) should contain("Type.Tuple")
    kinds.map(_.name) should contain("Pattern.Tuple")
    kinds.map(_.name) should contain("ErrorTree")

    // Check forms
    kinds.find(_.name == "ErrorTree").get.form shouldBe "case-class"
    kinds.find(_.name == "Decl.Def").get.form shouldBe "case-object"
  }

  test("calculateDigest should produce deterministic SHA-256 string") {
    val kinds = TreeKindExtractor.extractTreeKinds()
    val digest1 = TreeKindExtractor.calculateDigest(kinds)
    val digest2 = TreeKindExtractor.calculateDigest(kinds)

    digest1 shouldBe digest2
    digest1.length shouldBe 64
    digest1.matches("^[a-f0-9]{64}$") shouldBe true
  }

  test("ast/treekind.json should match extracted kinds and pin digest") {
    val kinds = TreeKindExtractor.extractTreeKinds()
    val digest = TreeKindExtractor.calculateDigest(kinds)
    val path = Paths.get("../../ast/treekind.json").toAbsolutePath.normalize()

    if (Files.exists(path)) {
      val content = Files.readString(path)
      content should include(s""""treeKindDigest": "$digest"""")
      content should include(s""""treeKindCount": 192""")
    }
  }
}
