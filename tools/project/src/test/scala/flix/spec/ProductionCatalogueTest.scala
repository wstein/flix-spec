package flix.spec

import java.nio.file.Paths
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ProductionCatalogueTest extends AnyFunSuite with Matchers {
  private val root = Paths.get("../..").toAbsolutePath.normalize()
  private def variant(id: String): Json =
    Json
      .parseFile(root.resolve("ast/syntax-variants.json"))("variants")
      .asArray
      .find(_("id").asString == id)
      .getOrElse(fail(s"missing variant $id"))

  private def witness(id: String): Unit = {
    val entry = variant(id)("evidence").asArray.head
    val source = entry("fixture").asString
    val name = source.split('/').last.stripSuffix(".flix") + ".json"
    val unit = Json
      .parseFile(root.resolve(s"fixtures/${if (entry("form").asString == "raw") "raw" else "expected"}/$name"))("units")
      .asArray
      .find(_("source").asString == source)
      .get
    val nodes = SyntaxCoverage.nodes(unit("tree"))
    entry("matches").asArray.foreach { p =>
      withClue(id) { nodes.exists(SyntaxCoverage.matches(_, p)) shouldBe true }
      // Rotate the matched anchor's direct children: keep every kind and token,
      // but destroy the production decision rather than its provenance.
      val anchor = nodes.find(SyntaxCoverage.matches(_, p)).get
      val children = anchor("children").asArray
      val mutated = Json.JObject(anchor.asObject.updated("children", Json.JArray(children.tail :+ children.head)))
      withClue(s"$id child-order mutation") { SyntaxCoverage.matches(mutated, p) shouldBe false }
    }
    SyntaxCoverage.evaluate(root).find(_.id == id).get.covered shouldBe true
  }

  private def isolatedEvidence(id: String): (java.nio.file.Path, java.nio.file.Path) = {
    import java.nio.file.Files
    val evidence = variant(id)("evidence").asArray.head
    val source = evidence("fixture").asString
    val form = if (evidence("form").asString == "raw") "raw" else "expected"
    val projection = s"fixtures/$form/${source.split('/').last.stripSuffix(".flix")}.json"
    val scratch = Files.createTempDirectory("production-catalogue-")
    List(
      "pin.json",
      "ast/treekind.json",
      "ast/tokenkind.json",
      "ast/syntax-variants.json",
      "schemas/syntax-variants.schema.json",
      source,
      projection
    ).foreach { file =>
      Files.createDirectories(scratch.resolve(file).getParent)
      Files.copy(root.resolve(file), scratch.resolve(file))
    }
    SyntaxCoverage.evaluate(scratch).find(_.id == id).get.covered shouldBe true
    (scratch, scratch.resolve(projection))
  }

  test("production evidence gate rejects changed token text without changing provenance") {
    val ids = Json
      .parseFile(root.resolve("ast/syntax-variants.json"))("variants")
      .asArray
      .map(_("id").asString)
      .filter(id =>
        List("expression.", "type.arrow.", "interpolation.", "declaration.prefix.", "recovery.").exists(id.startsWith)
      )
    ids.size shouldBe 11
    ids.foreach { id =>
      val (scratch, projection) = isolatedEvidence(id)
      val p = variant(id)("evidence").asArray.head("matches").asArray.head
      val token = SyntaxCoverage.nodes(p).find(_.get("text").isDefined).get
      val before = "\"text\":\"" + Json.esc(token("text").asString) + "\""
      val text = java.nio.file.Files.readString(projection)
      text should include(before)
      java.nio.file.Files.writeString(projection, text.replace(before, "\"text\":\"changed-production-token\""))
      withClue(id) {
        SyntaxCoverage
          .evaluate(scratch)
          .find(_.id == id)
          .get
          .errors
          .exists(_.contains("missing predicate")) shouldBe true
      }
    }
  }

  test("EOF diagnostic gate rejects both changed kind and changed line") {
    List(
      "\"kind\":\"UnterminatedStringInterpolation\"" -> "\"kind\":\"UnexpectedToken\"",
      "\"line\":1,\"col\":19" -> "\"line\":2,\"col\":19"
    ).foreach { case (before, after) =>
      val (scratch, projection) = isolatedEvidence("recovery.interpolation-eof")
      val text = java.nio.file.Files.readString(projection)
      text should include(before)
      java.nio.file.Files.writeString(projection, text.replace(before, after))
      SyntaxCoverage
        .evaluate(scratch)
        .find(_.id == "recovery.interpolation-eof")
        .get
        .errors
        .exists(_.contains("diagnostic kind/line mismatch")) shouldBe true
    }
  }

  test("precedence evidence distinguishes tighter multiplication and both associativities") {
    List(
      "expression.precedence.multiply-in-add",
      "expression.associativity.cons-right",
      "expression.associativity.subtract-left"
    ).foreach(witness)
  }
  test("interpolation evidence distinguishes nested and adjacent expression boundaries") {
    List("interpolation.nested", "interpolation.adjacent").foreach(witness)
  }
  test("annotation and modifier evidence keeps non-singular lists on the declaration") {
    List("declaration.prefix.single", "declaration.prefix.multiple").foreach(witness)
  }
  test("current consumer type-arrow divergences retain right nesting and effect binding") {
    List("type.arrow.right-associative", "type.arrow.result-effect").foreach(witness)
  }
  test("recovery evidence retains missing-operator and synthetic EOF structure") {
    List("recovery.operator-gap", "recovery.interpolation-eof").foreach(witness)
  }
}
