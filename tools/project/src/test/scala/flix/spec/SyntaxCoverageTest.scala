package flix.spec

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SyntaxCoverageTest extends AnyFunSuite with Matchers {
  private def j(s: String): Json = Json.parse(s)
  private val tree = j(
    """{"kind":"Type.Schema","children":[{"token":"HashCurlyL","text":"#{"},{"kind":"Type.Variable","children":[{"token":"NameLowerCase","text":"r"}]},{"token":"CurlyR","text":"}"}]}"""
  )

  test("matches a nested ordered token and kind structure") {
    SyntaxCoverage.matches(
      tree,
      j(
        """{"kind":"Type.Schema","children":[{"token":"HashCurlyL","text":"#{"},{"kind":"Type.Variable","children":[{"token":"NameLowerCase","text":"r"}]},{"token":"CurlyR","text":"}"}]}"""
      )
    ) shouldBe true
  }
  test("wrong token identity, text, kind and child order each fail") {
    List("""{"token":"HashParenL"}""", """{"token":"HashCurlyL","text":"#("}""", """{"kind":"Type.Extensible"}""")
      .foreach { p =>
        SyntaxCoverage.nodes(tree).exists(SyntaxCoverage.matches(_, j(p))) shouldBe false
      }
    SyntaxCoverage.matches(
      tree,
      j("""{"kind":"Type.Schema","children":[{"token":"CurlyR"},{"kind":"Type.Variable"}]}""")
    ) shouldBe false
  }
  test("child constraints are direct and preserve multiplicity") {
    SyntaxCoverage.matches(tree, j("""{"kind":"Type.Schema","children":[{"token":"NameLowerCase"}]}""")) shouldBe false
    SyntaxCoverage.matches(
      tree,
      j("""{"kind":"Type.Schema","children":[{"kind":"Type.Variable"},{"kind":"Type.Variable"}]}""")
    ) shouldBe false
  }
  test("exact children rejects extra children while subsequence allows them") {
    val p = """{"kind":"Type.Schema","children":[{"kind":"Type.Variable"}]}"""
    SyntaxCoverage.matches(tree, j(p)) shouldBe true
    SyntaxCoverage.matches(tree, j(p.dropRight(1) + ",\"exactChildren\":true}")) shouldBe false
  }
  test("descendant constraints remain under the selected anchor") {
    SyntaxCoverage.matches(
      tree,
      j("""{"kind":"Type.Schema","descendants":[{"token":"NameLowerCase","text":"r"}]}""")
    ) shouldBe true
    SyntaxCoverage.matches(
      tree,
      j("""{"kind":"Type.Variable","descendants":[{"token":"HashCurlyL"}]}""")
    ) shouldBe false
  }
  test("empty or unknown predicates and invalid vocabulary are rejected") {
    val kinds = Set("Type.Schema", "Type.Variable")
    val tokens = Set("NameLowerCase")
    List(
      "{}",
      """{"kind":"Bogus"}""",
      """{"token":"Bogus"}""",
      """{"kind":"Type.Schema","descendent":[]}""",
      """{"text":"r"}"""
    ).foreach { p =>
      SyntaxCoverage.patternErrors(j(p), kinds, tokens) should not be empty
    }
    SyntaxCoverage.patternErrors(
      j("""{"kind":"Type.Schema","descendants":[{"token":"NameLowerCase"}]}"""),
      kinds,
      tokens
    ) shouldBe empty
  }
  test("firstChild checks the first direct child") {
    SyntaxCoverage.matches(tree, j("""{"kind":"Type.Schema","firstChild":{"token":"HashCurlyL"}}""")) shouldBe true
    SyntaxCoverage.matches(tree, j("""{"kind":"Type.Schema","firstChild":{"kind":"Type.Variable"}}""")) shouldBe false
  }

  private def sandbox(): java.nio.file.Path = {
    import java.nio.file.{Files, Paths}
    val root = Files.createTempDirectory("syntax-coverage-test-")
    List("ast", "schemas", "fixtures/positive", "fixtures/raw").foreach(p => Files.createDirectories(root.resolve(p)))
    Files.copy(
      Paths.get("../../schemas/syntax-variants.schema.json"),
      root.resolve("schemas/syntax-variants.schema.json")
    )
    def put(path: String, text: String): Unit = { Files.writeString(root.resolve(path), text); () }
    put(
      "pin.json",
      """{"upstream":{"commit":"1111111111111111111111111111111111111111","tag":"v0.79.0"},"oracleArtifact":{"sha256":"test-digest"}}"""
    )
    put("ast/treekind.json", """{"kinds":[{"name":"Type.Schema"},{"name":"Type.Variable"}]}""")
    put("ast/tokenkind.json", """{"kinds":[{"name":"HashCurlyL"},{"name":"CurlyR"},{"name":"NameLowerCase"}]}""")
    put("fixtures/positive/example.flix", "test")
    put(
      "fixtures/raw/example.json",
      """{"form":"raw","upstreamCommit":"1111111111111111111111111111111111111111","oracleSha256":"test-digest","units":[{"source":"fixtures/positive/example.flix","diagnostics":[],"tree":{"kind":"Type.Schema","children":[{"kind":"Type.Variable","children":[{"token":"NameLowerCase","text":"r"}]}]}}]}"""
    )
    put(
      "ast/syntax-variants.json",
      """{"schemaVersion":1,"upstreamCommit":"1111111111111111111111111111111111111111","upstreamTag":"v0.79.0","scope":"test","variants":[{"id":"schema.variable","production":"schemaType","description":"variable tail","upstreamSource":"main/src/ca/uwaterloo/flix/language/phase/Parser2.scala:3800-3814","evidence":[{"fixture":"fixtures/positive/example.flix","form":"raw","diagnostics":[],"matches":[{"kind":"Type.Schema","children":[{"kind":"Type.Variable","children":[{"token":"NameLowerCase","text":"r"}]}]}],"absent":[{"token":"CurlyR"}]}]}]}"""
    )
    root
  }

  private def replace(root: java.nio.file.Path, file: String, before: String, after: String): Unit = {
    val path = root.resolve(file)
    val text = java.nio.file.Files.readString(path)
    require(text.contains(before), s"test mutation target missing: $before")
    java.nio.file.Files.writeString(path, text.replace(before, after))
    ()
  }

  test("evidence gate covers a valid declared variant") {
    SyntaxCoverage.evaluate(sandbox()).map(_.covered) shouldBe List(true)
  }
  test("missing fixture and projection are reported per variant") {
    val root = sandbox()
    java.nio.file.Files.delete(root.resolve("fixtures/positive/example.flix"))
    java.nio.file.Files.delete(root.resolve("fixtures/raw/example.json"))
    val errors = SyntaxCoverage.evaluate(root).head.errors
    errors.exists(_.contains("missing source fixture")) shouldBe true
    errors.exists(_.contains("missing raw projection")) shouldBe true
  }
  test("wrong source identity and wrong form fail") {
    List("fixtures/positive/example.flix" -> "fixtures/positive/other.flix", "\"raw\"" -> "\"normalized\"").foreach {
      case (a, b) =>
        val root = sandbox()
        replace(root, "fixtures/raw/example.json", a, b)
        SyntaxCoverage.evaluate(root).head.covered shouldBe false
    }
  }
  test("a lost tail, wrong token text and wrong diagnostic kind or line fail") {
    List(
      "Type.Variable" -> "Type.Schema",
      "\"text\":\"r\"" -> "\"text\":\"s\"",
      "\"diagnostics\":[]" -> "\"diagnostics\":[{\"kind\":\"UnexpectedToken\",\"line\":3}]"
    ).foreach { case (a, b) =>
      val root = sandbox()
      replace(root, "fixtures/raw/example.json", a, b)
      SyntaxCoverage.evaluate(root).head.covered shouldBe false
    }
  }
  test("forbidden structure and pin drift fail") {
    val root = sandbox()
    replace(root, "ast/syntax-variants.json", "\"token\":\"CurlyR\"", "\"token\":\"NameLowerCase\"")
    SyntaxCoverage.evaluate(root).head.errors.exists(_.contains("forbidden predicate")) shouldBe true
    replace(root, "pin.json", "v0.79.0", "v0.80.0")
    intercept[IllegalArgumentException](SyntaxCoverage.evaluate(root)).getMessage should include("upstreamTag")
  }
  test("catalogue rejects malformed shape") {
    val root = sandbox()
    replace(root, "ast/syntax-variants.json", "\"matches\":[", "\"typo\":[")
    intercept[IllegalArgumentException](SyntaxCoverage.evaluate(root)).getMessage should include("matches")
  }
  test("duplicate variant ids and projection provenance drift fail") {
    val root = sandbox()
    replace(root, "fixtures/raw/example.json", "test-digest", "wrong-digest")
    SyntaxCoverage.evaluate(root).head.errors.exists(_.contains("provenance")) shouldBe true
    val path = root.resolve("ast/syntax-variants.json")
    val text = java.nio.file.Files.readString(path)
    val prefixEnd = text.indexOf("[{\"id\":") + 1
    val variant = text.substring(prefixEnd, text.length - 2)
    java.nio.file.Files.writeString(path, text.substring(0, prefixEnd) + variant + "," + variant + "]}")
    intercept[IllegalArgumentException](SyntaxCoverage.evaluate(root)).getMessage should include("duplicate")
  }
}
