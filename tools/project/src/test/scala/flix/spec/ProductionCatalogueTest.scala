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
}
