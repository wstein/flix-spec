package flix.spec

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._

/** Structural validation of both committed forms -- `fixtures/raw&#47;*.json` and `fixtures/expected&#47;*.json` --
  * against `schemas/projection.schema.json`, replacing the former `tools/project/validate-projection.py`. Run from the
  * repository root.
  *
  * Both, not one. The normalised trees are the ones consumers compare against, but the raw trees are what the recovery
  * lane compares against and what every measurement of the reference's own vocabulary is taken from; a malformed
  * document in either is a malformed published artifact.
  *
  * Two checks the schema alone cannot express, on top of [[SchemaValidator]]'s generic walk:
  *
  *   - every `kind` must exist in `ast/treekind.json`, and every `token` in `ast/tokenkind.json` -- the schema can only
  *     say "a string", but a projected tree whose vocabulary has drifted from the inventory is exactly the failure this
  *     repository exists to catch;
  *   - `source` must be repository-relative, and diagnostic messages must not embed an absolute path. Either would make
  *     a committed expectation machine-specific and fail the diff gate on any other checkout.
  *
  * `Node.children` uses `oneOf` between `Node` and `TokenNode`, which [[SchemaValidator]] deliberately does not walk
  * generically -- the actual shape of a child (does it have `kind`?) decides which alternative applies, and that
  * discrimination happens here.
  */
object ProjectionSchemaValidator {

  /** Walks one projected tree, checking node shape and -- optionally -- vocabulary.
    *
    * The inventories are `Option` because the two callers ask different questions of the same walk. Validating
    * `fixtures/expected` checks vocabulary: those trees come from the reference, so a kind outside `ast/treekind.json`
    * is a generator bug. [[Conformance]]'s source-invariants lane checks the *consumer's* output, which legitimately
    * carries that consumer's own native vocabulary whenever a projection map is in play -- there, an unrecognised name
    * is what the map exists to translate, and reporting it here would double-count it as a defect. Passing `None` asks
    * only "is this a well-formed document", which is true of both.
    */
  def walk(
      node: Json,
      path: String,
      inventory: Option[Set[String]],
      tokenInventory: Option[Set[String]],
      kindsSeen: scala.collection.mutable.Set[String],
      tokensSeen: scala.collection.mutable.Set[String],
      errors: SchemaValidator.Errors
  ): Unit = {
    if (node.get("kind").isEmpty) {
      // Token leaf.
      List("token", "text", "start", "end").foreach { key =>
        if (node.get(key).isEmpty) errors.add(s"$path: token node missing '$key'")
      }
      // The schema can only say `token` is a string. Checking it against the inventory is the
      // lexical counterpart of the `kind` check: without it a renamed or misspelled token name
      // passes every gate, which is precisely the drift this repository exists to catch.
      node.get("token").map(_.asString).foreach { token =>
        tokensSeen += token
        tokenInventory.foreach { known =>
          if (!known.contains(token)) errors.add(s"$path: token '$token' is not in ast/tokenkind.json")
        }
      }
      return
    }
    val kind = node("kind").asString
    kindsSeen += kind
    inventory.foreach { known =>
      if (!known.contains(kind)) errors.add(s"$path: kind '$kind' is not in ast/treekind.json")
    }
    node.get("children").map(_.asArray).getOrElse(Nil).zipWithIndex.foreach { case (child, i) =>
      walk(child, s"$path.children[$i]", inventory, tokenInventory, kindsSeen, tokensSeen, errors)
    }
  }

  private val DriveLetter = "^[A-Za-z]:".r

  def main(args: Array[String]): Unit = {
    val schema = Json.parseFile(Paths.get("schemas/projection.schema.json"))
    val inventory = Json.parseFile(Paths.get("ast/treekind.json"))("kinds").asArray.map(_("name").asString).toSet
    val tokenInventory =
      Json.parseFile(Paths.get("ast/tokenkind.json"))("kinds").asArray.map(_("name").asString).toSet
    val errors = new SchemaValidator.Errors

    val dirs = List(ProjectionExtractor.RawDir, ProjectionExtractor.NormalizedDir)
    val files = dirs.flatMap { dir =>
      val path = Paths.get(dir)
      if (!Files.isDirectory(path)) {
        System.err.println(s"FATAL: $dir/ does not exist — run generateFixtures")
        sys.exit(1)
      }
      Files.list(path).iterator().asScala.map(_.toString).filter(_.endsWith(".json")).toList.sorted
    }

    if (files.isEmpty) {
      System.err.println(s"FATAL: no projected trees found in ${dirs.mkString(" or ")}")
      sys.exit(1)
    }

    val allKinds = scala.collection.mutable.Set.empty[String]
    val allTokens = scala.collection.mutable.Set.empty[String]
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

        unit
          .get("tree")
          .foreach(tree => walk(tree, s"$p.tree", Some(inventory), Some(tokenInventory), allKinds, allTokens, errors))
      }
    }

    if (!errors.isEmpty) {
      System.err.println("FATAL: projection validation failed")
      errors.toList.foreach(e => System.err.println(s"  $e"))
      sys.exit(1)
    }

    println(
      s"OK: ${files.length} expectation(s) conform to projection.schema.json; " +
        s"${allKinds.size} distinct kinds and ${allTokens.size} distinct tokens, " +
        s"all present in ast/treekind.json and ast/tokenkind.json"
    )
  }
}
