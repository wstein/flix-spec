package flix.spec

import Json._

/** The subset of JSON Schema draft-07 used by this repository. Constraints are evaluated at every schema position,
  * including referenced definitions, inline objects, array items and `oneOf` branches.
  */
object SchemaValidator {

  /** Every JSON Schema keyword this validator actually acts on, plus the annotations it may ignore.
    *
    * Declared here so `SchemaValidatorTest` can assert that nothing under `schemas/` uses a keyword outside it. A
    * schema may otherwise state a constraint the validator silently ignores, which is worse than not stating it: the
    * file reads as though the rule is enforced and no run can disagree. `minItems` sat in
    * `schemas/defect-ledger.schema.json` in exactly that state.
    */
  val KnownKeywords: Set[String] = Set(
    // structural
    "type",
    "properties",
    "required",
    "additionalProperties",
    "items",
    "definitions",
    "$ref",
    "oneOf",
    // value constraints
    "enum",
    "minimum",
    "maximum",
    "minLength",
    "minItems",
    "uniqueItems",
    "pattern",
    // annotations, carried for readers and deliberately not enforced
    "$id",
    "$schema",
    "title",
    "description",
    "deprecated"
  )

  final class Errors {
    private val buf = scala.collection.mutable.ArrayBuffer.empty[String]
    def add(message: String): Unit = buf += message
    def toList: List[String] = buf.toList
    def isEmpty: Boolean = buf.isEmpty
  }

  private def typeNames(spec: Json): List[String] = spec match {
    case JArray(items) => items.map(_.asString)
    case JString(name) => List(name)
    case _             => Nil
  }

  private def matchesType(name: String, value: Json): Boolean = name match {
    case "integer" =>
      value match {
        case JNumber(n) => n.isWhole
        case _          => false
      }
    case "number"  => value.isInstanceOf[JNumber]
    case "string"  => value.isInstanceOf[JString]
    case "array"   => value.isInstanceOf[JArray]
    case "object"  => value.isInstanceOf[JObject]
    case "boolean" => value.isInstanceOf[JBool]
    case "null"    => value.isNull
    case _         => true
  }

  private def resolveRef(ref: String, root: Json): Json = {
    require(ref.startsWith("#/"), s"unsupported schema reference: $ref")
    ref.drop(2).split("/").foldLeft(root) { (value, key) =>
      value
        .get(key.replace("~1", "/").replace("~0", "~"))
        .getOrElse(throw new IllegalArgumentException(s"unresolved schema reference: $ref"))
    }
  }

  def check(obj: Json, schema: Json, root: Json, path: String, errors: Errors): Unit = {
    schema.get("$ref") match {
      case Some(ref) =>
        check(obj, resolveRef(ref.asString, root), root, path, errors)
        return
      case None =>
    }

    schema.get("type").map(typeNames).foreach { names =>
      if (names.nonEmpty && !names.exists(matchesType(_, obj)))
        errors.add(s"$path: expected ${names.mkString(" or ")}, got $obj")
    }
    schema.get("oneOf").foreach { alternatives =>
      val branches = alternatives.asArray.map { alternative =>
        val branchErrors = new Errors
        check(obj, alternative, root, path, branchErrors)
        branchErrors.toList
      }
      val matching = branches.count(_.isEmpty)
      if (matching != 1) errors.add(s"$path: expected exactly one oneOf alternative, matched $matching")
      // Report the closest alternative as well, so a malformed token identifies the missing field.
      if (matching == 0 && branches.nonEmpty) branches.minBy(_.length).foreach(errors.add)
    }
    schema.get("enum").foreach { allowed =>
      if (!allowed.asArray.contains(obj)) errors.add(s"$path: $obj not in ${allowed.asArray}")
    }
    (schema.get("minimum"), obj) match {
      case (Some(JNumber(min)), JNumber(value)) if value < min =>
        errors.add(s"$path: $value < minimum $min")
      case _ =>
    }
    (schema.get("maximum"), obj) match {
      case (Some(JNumber(max)), JNumber(value)) if value > max =>
        errors.add(s"$path: $value > maximum $max")
      case _ =>
    }
    (schema.get("uniqueItems"), obj) match {
      case (Some(JBool(true)), JArray(items)) if items.distinct.length != items.length =>
        errors.add(s"$path: repeated item(s) ${items.diff(items.distinct).distinct.mkString(", ")}")
      case _ =>
    }
    (schema.get("minItems"), obj) match {
      case (Some(min), JArray(items)) if items.length < min.asInt =>
        errors.add(s"$path: ${items.length} item(s), fewer than minItems ${min.asInt}")
      case _ =>
    }
    (schema.get("minLength"), obj) match {
      case (Some(min), JString(value)) if value.codePointCount(0, value.length) < min.asInt =>
        errors.add(s"$path: shorter than minLength ${min.asInt}")
      case _ =>
    }
    (schema.get("pattern"), obj) match {
      case (Some(pattern), JString(value)) if pattern.asString.r.findFirstIn(value).isEmpty =>
        errors.add(s"$path: '$value' does not match ${pattern.asString}")
      case _ =>
    }

    obj match {
      case JObject(fields) =>
        val props = schema.get("properties").map(_.asObject).getOrElse(Map.empty)
        schema.get("required").map(_.asArray).getOrElse(Nil).foreach { req =>
          if (!fields.contains(req.asString)) errors.add(s"$path: missing required key '${req.asString}'")
        }
        if (schema.get("additionalProperties").contains(JBool(false)))
          fields.keys.foreach(key => if (!props.contains(key)) errors.add(s"$path: unexpected key '$key'"))
        props.foreach { case (key, spec) =>
          fields.get(key).foreach(value => check(value, spec, root, s"$path.$key", errors))
        }
      case JArray(items) =>
        schema.get("items").foreach { itemSchema =>
          items.zipWithIndex.foreach { case (item, i) => check(item, itemSchema, root, s"$path[$i]", errors) }
        }
      case _ =>
    }
  }
}
