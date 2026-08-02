package flix.spec

import java.nio.file.Paths

/** Structural validation of `ast/treekind.json` against `schemas/treekind.schema.json`, replacing the former
  * `tools/project/validate-treekind.py`. Run from the repository root.
  *
  * Also cross-checks that `treeKindCount` agrees with `kinds.length` rather than only with itself -- the schema alone
  * cannot express that relationship.
  */
object TreeKindSchemaValidator {

  def main(args: Array[String]): Unit = {
    val schema = Json.parseFile(Paths.get("schemas/treekind.schema.json"))
    val doc = Json.parseFile(Paths.get("ast/treekind.json"))
    val errors = new SchemaValidator.Errors

    SchemaValidator.check(doc, schema, schema, "treekind.json", errors)

    val declaredCount = doc("treeKindCount").asInt
    val actualCount = doc.get("kinds").map(_.asArray.length).getOrElse(0)
    if (declaredCount != actualCount)
      errors.add(s"treeKindCount $declaredCount != len(kinds) $actualCount")

    if (!errors.isEmpty) {
      System.err.println("FATAL: schema validation failed")
      errors.toList.foreach(e => System.err.println(s"  $e"))
      sys.exit(1)
    }
    println(s"OK: conforms to treekind.schema.json ($actualCount kinds validated)")
  }
}
