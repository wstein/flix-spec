package flix.spec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner
import org.junit.runner.RunWith

import java.nio.file.{Files, Path, Paths}

@RunWith(classOf[JUnitRunner])
class TreeKindExtractorTest extends AnyFunSuite with Matchers {

  /** Repository root, resolved from the Gradle module dir. Asserted rather than probed: a wrong path must fail the
    * suite, not quietly skip the assertions that depend on it.
    */
  private val repoRoot: Path = Paths.get("../..").toAbsolutePath.normalize()

  private val oracleJar: Path = repoRoot.resolve(".oracle/flix.jar")

  test("repository layout resolves") {
    withClue(s"repoRoot=$repoRoot ") {
      Files.exists(repoRoot.resolve("pin.json")) shouldBe true
      Files.exists(oracleJar) shouldBe true
    }
  }

  test("extractTreeKinds should return exactly 192 unique qualified kinds") {
    val kinds = TreeKindExtractor.extractTreeKinds(oracleJar)

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

  test("form is decided by MODULE$, not by a hardcoded name") {
    val kinds = TreeKindExtractor.extractTreeKinds(oracleJar)

    // ErrorTree is the only case class at this pin, but the generator must not know that by
    // name -- it must reach the same answer reflectively.
    kinds.count(_.form == "case-class") shouldBe 1
    kinds.count(_.form == "case-object") shouldBe 191
  }

  test("every declared parent is TreeKind or a real sub-trait") {
    val kinds = TreeKindExtractor.extractTreeKinds(oracleJar)
    val parents = kinds.map(_.`extends`).toSet

    parents should contain("TreeKind")
    parents.filterNot(_ == "TreeKind") shouldBe
      Set("Decl", "Expr", "Type", "Pattern", "Predicate", "UsesOrImports")
  }

  test("calculateDigest should produce deterministic SHA-256 string") {
    val kinds = TreeKindExtractor.extractTreeKinds(oracleJar)
    val digest1 = TreeKindExtractor.calculateDigest(kinds)
    val digest2 = TreeKindExtractor.calculateDigest(kinds)

    digest1 shouldBe digest2
    digest1.length shouldBe 64
    digest1.matches("^[a-f0-9]{64}$") shouldBe true
  }

  test("pin.json records the count and digest the extractor computes") {
    val kinds = TreeKindExtractor.extractTreeKinds(oracleJar)
    val pin = Files.readString(repoRoot.resolve("pin.json"))

    // generateTreeKind asserts against these; if they drift, the failure should surface here
    // with a readable diff rather than as a require() deep inside a Gradle task.
    pin should include(s""""treeKindCount": ${kinds.length}""")
    pin should include(s""""treeKindDigest": "${TreeKindExtractor.calculateDigest(kinds)}"""")
  }

  test("the oracle jar on disk is the artifact pin.json names") {
    val pin = Files.readString(repoRoot.resolve("pin.json"))
    pin should include(s""""sha256": "${TreeKindExtractor.fileDigest(oracleJar)}"""")
  }

  test("ast/treekind.json matches extracted kinds, pin digest, and names its oracle") {
    val kinds = TreeKindExtractor.extractTreeKinds(oracleJar)
    val digest = TreeKindExtractor.calculateDigest(kinds)
    val path = repoRoot.resolve("ast/treekind.json")

    Files.exists(path) shouldBe true
    val content = Files.readString(path)
    content should include(s""""treeKindDigest": "$digest"""")
    content should include(s""""treeKindCount": ${kinds.length}""")

    // Provenance header (plan section 3.3): the artifact must name what produced it.
    content should include(s""""oracleSha256": "${TreeKindExtractor.fileDigest(oracleJar)}"""")
    content should include(s""""toolVersion": "${TreeKindExtractor.ToolVersion}"""")
    content should include("\"upstreamCommit\"")
  }
}
