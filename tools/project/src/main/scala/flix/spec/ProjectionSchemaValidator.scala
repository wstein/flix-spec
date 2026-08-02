package flix.spec

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._

/** Structural validation of `fixtures/expected&#47;*.json` against `schemas/projection.schema.json`, replacing the
  * former `tools/project/validate-projection.py`. Run from the repository root.
  *
  * Two checks the schema alone cannot express, on top of [[SchemaValidator]]'s generic walk:
  *
  *   - every `kind` must exist in `ast/treekind.json` -- the schema can only say "a string", but a projected tree whose
  *     vocabulary has drifted from the inventory is exactly the failure this repository exists to catch;
  *   - `source` must be repository-relative, and diagnostic messages must not embed an absolute path. Either would make
  *     a committed expectation machine-specific and fail the diff gate on any other checkout.
  *
  * `Node.children` uses `oneOf` between `Node` and `TokenNode`, which [[SchemaValidator]] deliberately does not walk
  * generically -- the actual shape of a child (does it have `kind`?) decides which alternative applies, and that
  * discrimination happens here.
  */
object ProjectionSchemaValidator {

  private def walk(
      node: Json,
      path: String,
      inventory: Set[String],
      kindsSeen: scala.collection.mutable.Set[String],
      errors: SchemaValidator.Errors
  ): Unit = {
    if (node.get("kind").isEmpty) {
      // Token leaf.
      List("token", "text", "start", "end").foreach { key =>
        if (node.get(key).isEmpty) errors.add(s"$path: token node missing '$key'")
      }
      return
    }
    val kind = node("kind").asString
    kindsSeen += kind
    if (!inventory.contains(kind)) errors.add(s"$path: kind '$kind' is not in ast/treekind.json")
    node.get("children").map(_.asArray).getOrElse(Nil).zipWithIndex.foreach { case (child, i) =>
      walk(child, s"$path.children[$i]", inventory, kindsSeen, errors)
    }
  }

  private val DriveLetter = "^[A-Za-z]:".r

  def main(args: Array[String]): Unit = {
    val schema = Json.parseFile(Paths.get("schemas/projection.schema.json"))
    val inventory = Json.parseFile(Paths.get("ast/treekind.json"))("kinds").asArray.map(_("name").asString).toSet
    val errors = new SchemaValidator.Errors

    val files = Files
      .list(Paths.get("fixtures/expected"))
      .iterator()
      .asScala
      .map(_.toString)
      .filter(_.endsWith(".json"))
      .toList
      .sorted

    if (files.isEmpty) {
      System.err.println("FATAL: no expectations found in fixtures/expected/")
      sys.exit(1)
    }

    val allKinds = scala.collection.mutable.Set.empty[String]
    files.foreach { f =>
      val doc = Json.parseFile(Paths.get(f))
      SchemaValidator.check(doc, schema, schema, f, errors)

      doc.get("units").map(_.asArray).getOrElse(Nil).zipWithIndex.foreach { case (unit, i) =>
        val p = s"$f.units[$i]"
        val src = unit.get("source").map(_.asString).getOrElse("")
        if (src.startsWith("/") || DriveLetter.findFirstIn(src).isDefined)
          errors.add(s"$p.source: '$src' is absolute; must be repository-relative")

        unit.get("diagnostics").map(_.asArray).getOrElse(Nil).zipWithIndex.foreach { case (d, j) =>
          val message = d.get("message").map(_.asString).getOrElse("")
          if (message.contains("/Users/") || message.contains("/home/"))
            errors.add(s"$p.diagnostics[$j].message: contains an absolute path")
        }

        unit.get("tree").foreach(tree => walk(tree, s"$p.tree", inventory, allKinds, errors))
      }
    }

    if (!errors.isEmpty) {
      System.err.println("FATAL: projection validation failed")
      errors.toList.foreach(e => System.err.println(s"  $e"))
      sys.exit(1)
    }

    println(
      s"OK: ${files.length} expectation(s) conform to projection.schema.json; " +
        s"${allKinds.size} distinct kinds, all present in ast/treekind.json"
    )
  }
}
