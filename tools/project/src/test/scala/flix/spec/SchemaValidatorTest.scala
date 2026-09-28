package flix.spec

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SchemaValidatorTest extends AnyFunSuite with Matchers {
  private val repoRoot: Path = Paths.get("../..").toAbsolutePath.normalize()

  private def errors(value: String, schema: Json, root: Json): List[String] = {
    val result = new SchemaValidator.Errors
    SchemaValidator.check(Json.parse(value), schema, root, "document", result)
    result.toList
  }

  test("nested projected nodes and tokens must satisfy their complete schemas") {
    val root = Json.parseFile(Paths.get("../../schemas/projection.schema.json"))
    val node = root("definitions")("Node")
    errors("""{"kind":"Root","children":[{"kind":"Decl.Def","children":[]}]}""", node, root) shouldBe Nil
    List(
      """{"kind":"Decl.Def"}""",
      """{"kind":"Decl.Def","children":[],"unexpected":true}""",
      """{"kind":"Decl.Def","children":[{"token":"Ident","text":"f"}]}""",
      """{"token":"Ident","text":42,"start":{"line":1,"col":1},"end":{"line":1,"col":2}}""",
      """{"token":"Ident","text":"f","start":{"line":1.5,"col":1},"end":{"line":1,"col":2}}"""
    ).foreach { child =>
      withClue(child) {
        errors(s"""{"kind":"Root","children":[$child]}""", node, root) should not be empty
      }
    }
  }

  test("referenced schemas enforce their own types") {
    val root = Json.parse("""{"definitions":{"Value":{"type":"integer"}},"$ref":"#/definitions/Value"}""")
    errors("1.5", root, root) should not be empty
    errors("1.0", root, root) shouldBe Nil
    errors("null", root, root) should not be empty
  }

  test("inline objects and scalar array items are validated recursively") {
    val schema = Json.parse(
      """{"type":"object","properties":{"nested":{"type":"object","required":["items"],"properties":{"items":{"type":"array","items":{"type":"string","pattern":"^ok$"}}}}}}"""
    )
    errors("""{"nested":{"items":["ok"]}}""", schema, schema) shouldBe Nil
    errors("""{"nested":{}}""", schema, schema) should not be empty
    errors("""{"nested":{"items":["wrong"]}}""", schema, schema) should not be empty
  }

  test("oneOf requires exactly one matching alternative") {
    val schema = Json.parse("""{"oneOf":[{"type":"integer"},{"type":"number"}]}""")
    errors("1.5", schema, schema) shouldBe Nil
    errors("1", schema, schema) should not be empty
    errors("null", schema, schema) should not be empty
  }

  test("minItems and maximum are enforced, not merely declared") {
    val schema = Json.parse("""{"type":"array","minItems":2}""")
    errors("""[1,2]""", schema, schema) shouldBe Nil
    errors("""[1]""", schema, schema) should not be empty

    val bounded = Json.parse("""{"type":"integer","minimum":0,"maximum":100}""")
    errors("100", bounded, bounded) shouldBe Nil
    errors("101", bounded, bounded) should not be empty
    errors("-1", bounded, bounded) should not be empty
  }

  test("no committed schema uses a keyword this validator ignores") {
    // A schema keyword nothing acts on is worse than an absent one: the file reads as though the
    // constraint is enforced and no run can disagree. `minItems` sat in defect-ledger.schema.json in
    // exactly that state, alone, for as long as the file existed.
    //
    // Keys *inside* `properties` and `definitions` are names the schema author chose, not keywords,
    // so the walk stops descending into them as keyword positions.
    def keywords(node: Json, inNames: Boolean): Set[String] = node match {
      case Json.JObject(fields) =>
        fields.flatMap { case (k, v) =>
          val here = if (inNames) Set.empty[String] else Set(k)
          here ++ keywords(v, inNames = k == "properties" || k == "definitions")
        }.toSet
      case Json.JArray(items) => items.flatMap(keywords(_, inNames)).toSet
      case _                  => Set.empty
    }

    val dir = repoRoot.resolve("schemas")
    val files = Files
      .list(dir)
      .iterator()
      .asScala
      .filter(_.getFileName.toString.endsWith(".json"))
      .toList
      .sortBy(_.toString)
    files should not be empty

    files.foreach { f =>
      val used = keywords(Json.parseFile(f), inNames = false)
      withClue(s"${f.getFileName} uses keyword(s) SchemaValidator does not act on: ") {
        (used -- SchemaValidator.KnownKeywords) shouldBe empty
      }
    }
  }
}
