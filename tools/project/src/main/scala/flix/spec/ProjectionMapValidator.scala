package flix.spec

import Json._
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._

/** Validates `ast/projection&#47;*.json` against `schemas/projection-map.schema.json`, replacing the former
  * `tools/project/validate-projection-map.py`. Run from the repository root.
  *
  * Beyond the schema, checks two things it cannot express:
  *
  *   - every `mappings` value and every `elide` entry must name a kind that exists in `ast/treekind.json` -- a typo or
  *     a stale kind name would otherwise silently never match and read as agreement;
  *   - a native node may not be both mapped and ignored, which is contradictory.
  */
object ProjectionMapValidator {

  def main(args: Array[String]): Unit = {
    val schemaPath = Paths.get("schemas/projection-map.schema.json")
    val schema = Json.parseFile(schemaPath)
    val inventory = Json.parseFile(Paths.get("ast/treekind.json"))("kinds").asArray.map(_("name").asString).toSet

    val mapsDir = Paths.get("ast/projection")
    val maps =
      if (Files.isDirectory(mapsDir))
        Files.list(mapsDir).iterator().asScala.map(_.toString).filter(_.endsWith(".json")).toList.sorted
      else Nil

    if (maps.isEmpty) {
      println("OK: no projection maps to validate")
      return
    }

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
      doc.get("elide").map(_.asArray.map(_.asString)).getOrElse(Nil).sorted.foreach { kind =>
        if (!inventory.contains(kind)) errors.add(s"$path.elide: '$kind' is not in ast/treekind.json")
      }

      val both = (mappings.keySet & ignored).toList.sorted
      if (both.nonEmpty) errors.add(s"$path: nodes both mapped and ignored: $both")

      doc.get("notes").map(_.asObject.keySet).getOrElse(Set.empty).toList.sorted.foreach { native =>
        val known = mappings.contains(native) || ignored.contains(native) || inventory.contains(native)
        if (!known) errors.add(s"$path.notes['$native']: notes an unknown node")
      }
    }

    if (!errors.isEmpty) {
      System.err.println("FATAL: projection map validation failed")
      errors.toList.foreach(e => System.err.println(s"  $e"))
      sys.exit(1)
    }

    val total = maps.map(p => Json.parseFile(Paths.get(p)).get("mappings").map(_.asObject.size).getOrElse(0)).sum
    println(s"OK: ${maps.length} projection map(s) valid, $total mappings, all targets in inventory")
  }
}
