package flix.spec

import Json._

/** The subset of JSON Schema draft-07 this repository's schemas actually use, shared by [[TreeKindSchemaValidator]] and
  * [[ProjectionSchemaValidator]] so the two do not hand-roll the same walk twice -- exactly the duplication this
  * repository exists to end elsewhere.
  *
  * Covers: `required`, `additionalProperties: false`, `type` (integer/string/array), `minimum`, `minLength`, `pattern`,
  * `enum`, and `$ref`'d array items. Deliberately does not cover `oneOf`/`allOf`: `schemas/projection.schema.json`'s
  * `Node.children` uses `oneOf` between `Node` and `TokenNode`, and a projected tree's actual shape (does this child
  * have a `kind`?) decides which one applies far more directly than a generic schema walk would -- that discrimination
  * belongs to [[ProjectionSchemaValidator]]'s own tree walk, not here.
  */
object SchemaValidator {

  final class Errors {
    private val buf = scala.collection.mutable.ArrayBuffer.empty[String]
    def add(message: String): Unit = buf += message
    def toList: List[String] = buf.toList
    def isEmpty: Boolean = buf.isEmpty
  }

  /** Resolves `#/definitions/Foo` against `root` (the whole schema document). */
  private def resolveRef(ref: String, root: Json): Json = {
    val name = ref.split("/").last
    root("definitions")(name)
  }

  def check(obj: Json, schema: Json, root: Json, path: String, errors: Errors): Unit = {
    val props = schema.get("properties").map(_.asObject).getOrElse(Map.empty)

    schema.get("required").map(_.asArray).getOrElse(Nil).foreach { req =>
      val key = req.asString
      if (obj.get(key).isEmpty) errors.add(s"$path: missing required key '$key'")
    }

    if (schema.get("additionalProperties").contains(JBool(false))) {
      obj match {
        case JObject(fields) =>
          fields.keys.foreach(key => if (!props.contains(key)) errors.add(s"$path: unexpected key '$key'"))
        case _ => // not an object; type mismatch is reported by the caller's own required/type checks
      }
    }

    props.foreach { case (key, spec) =>
      obj.get(key).foreach { value =>
        val p = s"$path.$key"

        spec.get("$ref") match {
          case Some(ref) =>
            // A $ref'd property is validated entirely by recursing into the referenced
            // definition; it carries no type/minimum/pattern of its own to also check here.
            check(value, resolveRef(ref.asString, root), root, p, errors)

          case None =>
            spec.get("type").map(_.asString) match {
              case Some("integer") if !value.isInstanceOf[JNumber] =>
                errors.add(s"$p: expected integer, got $value")
              case Some("string") if !value.isInstanceOf[JString] =>
                errors.add(s"$p: expected string, got $value")
              case Some("array") if !value.isInstanceOf[JArray] =>
                errors.add(s"$p: expected array, got $value")
              case _ =>
            }

            (spec.get("minimum"), value) match {
              case (Some(min), JNumber(v)) if v < BigDecimal(min.asInt) =>
                errors.add(s"$p: $v < minimum ${min.asInt}")
              case _ =>
            }
            (spec.get("minLength"), value) match {
              case (Some(min), JString(v)) if v.length < min.asInt =>
                errors.add(s"$p: shorter than minLength ${min.asInt}")
              case _ =>
            }
            (spec.get("pattern"), value) match {
              case (Some(pat), JString(v)) if !v.matches(pat.asString) =>
                errors.add(s"$p: '$v' does not match ${pat.asString}")
              case _ =>
            }
            (spec.get("enum"), value) match {
              case (Some(allowed), JString(v)) if !allowed.asArray.map(_.asString).contains(v) =>
                errors.add(s"$p: '$v' not in ${allowed.asArray.map(_.asString)}")
              case _ =>
            }

            value match {
              case JArray(items) =>
                spec.get("items").flatMap(_.get("$ref")).foreach { ref =>
                  val itemSchema = resolveRef(ref.asString, root)
                  items.zipWithIndex.foreach { case (item, i) => check(item, itemSchema, root, s"$p[$i]", errors) }
                }
              case _ =>
            }
        }
      }
    }
  }
}
