package flix.spec

import java.nio.file.Paths
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SchemaValidatorTest extends AnyFunSuite with Matchers {
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
}
