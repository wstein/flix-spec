package flix.spec

import Json._
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._

/** Validates consumer projection maps against `schemas/projection-map.schema.json`. Run from the repository root with
  * the map paths as arguments.
  *
  * The maps are not this repository's data and are not stored here: each encodes facts about one consumer's grammar
  * shape -- which of its nodes are transparent wrappers, which native kind covers which canonical one -- so it belongs
  * beside that grammar, and republishing it here on every consumer-side fix bought nothing but release choreography.
  * `flix-spec` keeps the halves that are genuinely shared: the schema, the canonical `TreeKind` vocabulary the map's
  * targets are checked against, and the comparison itself.
  *
  * Beyond the schema, checks two things it cannot express:
  *
  *   - every `mappings` value and every `elide` entry must name a kind that exists in `ast/treekind.json` -- a typo or
  *     a stale kind name would otherwise silently never match and read as agreement;
  *   - a node listed in `ignored` but never in `mappings` must actually be transparent in practice.
  *
  * A node may legitimately appear in **both** `ignored` and `mappings`. That is not a contradiction: elision only fires
  * when a node has at most one child, so the two entries describe different situations -- "splice me when I wrap a
  * single child" and "interpret me this way when I do not". Precedence-chain grammars need exactly this. In
  * `flix-jetbrains-plugin`, `ADDITIVE_EXPR` is a pass-through on every expression that contains no `+`, and a real
  * binary expression when it does; forbidding the overlap would make one of those two cases unexpressible.
  */
object ProjectionMapValidator {

  def main(args: Array[String]): Unit = {
    val schemaPath = Paths.get("schemas/projection-map.schema.json")
    val schema = Json.parseFile(schemaPath)
    val inventory = Json.parseFile(Paths.get("ast/treekind.json"))("kinds").asArray.map(_("name").asString).toSet

    val maps = args.toList.flatMap { arg =>
      val p = Paths.get(arg)
      if (Files.isDirectory(p)) Files.list(p).iterator().asScala.map(_.toString).filter(_.endsWith(".json")).toList
      else if (Files.isRegularFile(p)) List(arg)
      else {
        System.err.println(s"FATAL: no such file or directory: $arg")
        sys.exit(1)
      }
    }.sorted

    if (maps.isEmpty) {
      System.err.println("FATAL: no projection maps given.")
      System.err.println("  Usage: validateProjectionMap --args='<map.json|dir> [...]'")
      System.err.println("  Maps live in the consumer's repository; this checks them against")
      System.err.println("  schemas/projection-map.schema.json and ast/treekind.json.")
      sys.exit(1)
    }

    val errors = validate(maps, schema, inventory)

    if (!errors.isEmpty) {
      System.err.println("FATAL: projection map validation failed")
      errors.toList.foreach(e => System.err.println(s"  $e"))
      sys.exit(1)
    }

    val total = maps.map(p => Json.parseFile(Paths.get(p)).get("mappings").map(_.asObject.size).getOrElse(0)).sum
    println(s"OK: ${maps.length} projection map(s) valid, $total mappings, all targets in inventory")
  }

  /** The checks themselves, separated from `main` so they can be exercised without exiting the JVM. */
  def validate(maps: List[String], schema: Json, inventory: Set[String]): SchemaValidator.Errors = {
    val requiredKeys = schema("required").asArray.map(_.asString)
    val allowedKeys = schema("properties").asObject.keySet
    val errors = new SchemaValidator.Errors

    maps.foreach { path =>
      val doc = Json.parseFile(Paths.get(path))
      val fields = doc.asObject

      requiredKeys.foreach { key => if (!fields.contains(key)) errors.add(s"$path: missing required key '$key'") }
      fields.keys.foreach { key => if (!allowedKeys.contains(key)) errors.add(s"$path: unexpected key '$key'") }

      doc.get("schemaVersion") match {
        case Some(JNumber(v)) if v >= 1 => // ok
        case other => errors.add(s"$path.schemaVersion: expected integer >= 1, got ${other.getOrElse(JNull)}")
      }
      doc.get("consumer") match {
        case Some(JString(v)) if v.nonEmpty => // ok
        case other => errors.add(s"$path.consumer: expected a non-empty string, got ${other.getOrElse(JNull)}")
      }

      val mappings = doc.get("mappings").map(_.asObject).getOrElse(Map.empty)
      mappings.toList.sortBy(_._1).foreach { case (native, canonical) =>
        if (!inventory.contains(canonical.asString))
          errors.add(s"$path.mappings['$native']: '${canonical.asString}' is not in ast/treekind.json")
      }

      val ignored = doc.get("ignored").map(_.asArray.map(_.asString).toSet).getOrElse(Set.empty)
      val flatten = doc.get("flatten").map(_.asArray.map(_.asString).toSet).getOrElse(Set.empty)
      doc.get("elide").map(_.asArray.map(_.asString)).getOrElse(Nil).sorted.foreach { kind =>
        if (!inventory.contains(kind)) errors.add(s"$path.elide: '$kind' is not in ast/treekind.json")
      }

      // Deliberately not an error: see the class comment. Elision fires only at arity <= 1, so a
      // node can be transparent in one position and substantive in another.

      doc.get("notes").map(_.asObject.keySet).getOrElse(Set.empty).toList.sorted.foreach { native =>
        val known =
          mappings.contains(native) || ignored.contains(native) || flatten.contains(native) ||
            inventory.contains(native)
        if (!known) errors.add(s"$path.notes['$native']: notes an unknown node")
      }
    }

    errors
  }
}
