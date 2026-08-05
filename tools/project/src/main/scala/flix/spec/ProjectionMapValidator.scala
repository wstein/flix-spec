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
  * Beyond the schema, checks four things it cannot express:
  *
  *   - every `mappings` value and every `elide` entry must name a kind that exists in `ast/treekind.json` -- a typo or
  *     a stale kind name would otherwise silently never match and read as agreement;
  *   - a node listed in `ignored` but never in `mappings` must actually be transparent in practice;
  *   - a node declared in `recoveryMarkers` may not also be flattened or ignored. Recovery markers are spliced out of
  *     the structural lane and kept in the recovery lane -- that asymmetry is the whole reason they are declared
  *     separately -- so removing one on both sides would leave its shape measured nowhere, silently, with the report
  *     still reading `pass`;
  *   - **no mapping may target a kind that `ast/transparency.json` removes.** This is the check that measurement asked
  *     for. Normalisation deletes those nodes from `fixtures/expected` before any consumer sees it, so a mapping onto
  *     one can never match -- it does not merely do nothing, it *manufactures* divergences, because the consumer's own
  *     node keeps standing where the canonical tree now has none. Both instrumented consumers carried such mappings
  *     after the contract landed, and on `flix-jetbrains-plugin` they were worth 31 of its 34 divergences. A kind the
  *     contract *splices* is the one exception, and only when the native node is declared in `recoveryMarkers`: the
  *     recovery lane keeps those on both sides, which is precisely what makes the mapping reachable there.
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
    val contract = Transparency.load()

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

    val errors = validate(maps, schema, inventory, contract)

    if (!errors.isEmpty) {
      System.err.println("FATAL: projection map validation failed")
      errors.toList.foreach(e => System.err.println(s"  $e"))
      sys.exit(1)
    }

    val total = maps.map(p => Json.parseFile(Paths.get(p)).get("mappings").map(_.asObject.size).getOrElse(0)).sum
    println(s"OK: ${maps.length} projection map(s) valid, $total mappings, all targets in inventory")
  }

  /** The checks themselves, separated from `main` so they can be exercised without exiting the JVM. */
  def validate(
      maps: List[String],
      schema: Json,
      inventory: Set[String],
      contract: Transparency.Contract
  ): SchemaValidator.Errors = {
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
      val recoveryMarkers = doc.get("recoveryMarkers").map(_.asArray.map(_.asString).toSet).getOrElse(Set.empty)
      mappings.toList.sortBy(_._1).foreach { case (native, target) =>
        val canonical = target.asString
        if (!inventory.contains(canonical))
          errors.add(s"$path.mappings['$native']: '$canonical' is not in ast/treekind.json")
        else if (contract.elide.contains(canonical))
          errors.add(
            s"$path.mappings['$native']: '$canonical' is elided by ast/transparency.json, so no canonical tree " +
              "contains it and this mapping can only manufacture divergences. Declare '" + native +
              "' in `ignored` instead."
          )
        else if (contract.splice.contains(canonical) && !recoveryMarkers.contains(native))
          errors.add(
            s"$path.mappings['$native']: '$canonical' is spliced out by ast/transparency.json, so this mapping is " +
              "unreachable in both lanes. Declare '" + native + "' in `recoveryMarkers` to make it reachable in " +
              "recovery_conformance, or drop the mapping."
          )
      }

      val ignored = doc.get("ignored").map(_.asArray.map(_.asString).toSet).getOrElse(Set.empty)
      val flatten = doc.get("flatten").map(_.asArray.map(_.asString).toSet).getOrElse(Set.empty)
      doc.get("elide").map(_.asArray.map(_.asString)).getOrElse(Nil).sorted.foreach { kind =>
        if (!inventory.contains(kind)) errors.add(s"$path.elide: '$kind' is not in ast/treekind.json")
      }
      // `flattenCanonical` names canonical kinds, so like `elide` -- and unlike `flatten`, which
      // names the consumer's own nodes -- every entry must exist in the inventory.
      doc.get("flattenCanonical").map(_.asArray.map(_.asString)).getOrElse(Nil).sorted.foreach { kind =>
        if (!inventory.contains(kind)) errors.add(s"$path.flattenCanonical: '$kind' is not in ast/treekind.json")
      }

      // Deliberately not an error: see the class comment. Elision fires only at arity <= 1, so a
      // node can be transparent in one position and substantive in another.

      // A recovery marker is spliced out of the structural lane and kept in the recovery lane, which
      // is the whole reason it is declared separately. Also flattening or ignoring it removes it
      // from both lanes, so its shape would be measured nowhere -- silently, and with the report
      // still reading `pass`.
      recoveryMarkers.intersect(flatten ++ ignored).toList.sorted.foreach { native =>
        errors.add(
          s"$path.recoveryMarkers: '$native' is also flattened or ignored, so its recovery shape " +
            "would be measured in neither lane"
        )
      }

      doc.get("notes").map(_.asObject.keySet).getOrElse(Set.empty).toList.sorted.foreach { native =>
        val known =
          mappings.contains(native) || ignored.contains(native) || flatten.contains(native) ||
            recoveryMarkers.contains(native) || inventory.contains(native)
        if (!known) errors.add(s"$path.notes['$native']: notes an unknown node")
      }
    }

    errors
  }
}
