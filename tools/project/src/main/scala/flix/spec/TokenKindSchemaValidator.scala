package flix.spec

import java.nio.file.Paths

/** Structural validation of `ast/tokenkind.json` against `schemas/tokenkind.schema.json`.
  *
  * Also cross-checks that `tokenKindCount` agrees with `kinds.length` rather than only with itself -- the schema alone
  * cannot express that relationship.
  */
object TokenKindSchemaValidator {

  def main(args: Array[String]): Unit = {
    val schema = Json.parseFile(Paths.get("schemas/tokenkind.schema.json"))
    val doc = Json.parseFile(Paths.get("ast/tokenkind.json"))
    val errors = new SchemaValidator.Errors

    SchemaValidator.check(doc, schema, schema, "tokenkind.json", errors)

    val declaredCount = doc("tokenKindCount").asInt
    val actualCount = doc.get("kinds").map(_.asArray.length).getOrElse(0)
    if (declaredCount != actualCount)
      errors.add(s"tokenKindCount $declaredCount != len(kinds) $actualCount")

    if (!errors.isEmpty) {
      System.err.println("FATAL: schema validation failed")
      errors.toList.foreach(e => System.err.println(s"  $e"))
      sys.exit(1)
    }
    println(s"OK: conforms to tokenkind.schema.json ($actualCount kinds validated)")
  }
}
