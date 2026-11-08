package flix.spec

import java.nio.file.{Files, Path, Paths}
import Json._

/** Checks a bounded, curated catalogue of production variants against committed projected trees. This is independent of
  * the census of kind names: evidence must satisfy a structural predicate.
  */
object SyntaxCoverage {
  def nodes(tree: Json): List[Json] =
    tree :: tree.get("children").map(_.asArray.flatMap(nodes)).getOrElse(Nil)

  /** Children are an ordered, non-reusing subsequence unless exactChildren is true. Descendants are searched strictly
    * below the same anchor; unrelated sibling evidence cannot satisfy them.
    */
  def matches(node: Json, pattern: Json): Boolean = {
    val identity = List("kind", "token", "text").forall(k => pattern.get(k).forall(v => node.get(k).contains(v)))
    val actual = node.get("children").map(_.asArray).getOrElse(Nil)
    val wanted = pattern.get("children").map(_.asArray).getOrElse(Nil)
    def subsequence(rest: List[Json], ps: List[Json]): Boolean = ps match {
      case Nil => true
      case p :: tail =>
        rest match {
          case Nil     => false
          case n :: ns => if (matches(n, p)) subsequence(ns, tail) else subsequence(ns, ps)
        }
    }
    val children =
      if (pattern.get("exactChildren").contains(JBool(true)))
        actual.length == wanted.length && actual.zip(wanted).forall { case (n, p) => matches(n, p) }
      else subsequence(actual, wanted)
    val descendants = pattern
      .get("descendants")
      .map(_.asArray)
      .getOrElse(Nil)
      .forall(p => actual.flatMap(nodes).exists(matches(_, p)))
    val first = pattern.get("firstChild").forall(p => actual.headOption.exists(matches(_, p)))
    identity && children && descendants && first
  }

  def patternErrors(pattern: Json, kinds: Set[String], tokens: Set[String]): List[String] = {
    val fields = pattern.asObject
    val unknown =
      fields.keySet.diff(Set("kind", "token", "text", "children", "exactChildren", "descendants", "firstChild"))
    val errors = List.newBuilder[String]
    if (unknown.nonEmpty) errors += s"unknown predicate keys: ${unknown.toList.sorted.mkString(",")}"
    if (!fields.contains("kind") && !fields.contains("token")) errors += "predicate needs kind or token"
    if (fields.contains("kind") && fields.contains("token")) errors += "predicate cannot be both node and token"
    pattern.get("kind").foreach(k => if (!kinds(k.asString)) errors += s"unknown kind ${k.asString}")
    pattern.get("token").foreach(t => if (!tokens(t.asString)) errors += s"unknown token ${t.asString}")
    if (fields.contains("text") && !fields.contains("token")) errors += "text requires token"
    List("children", "descendants").foreach(k =>
      pattern.get(k).foreach(_.asArray.foreach(p => errors ++= patternErrors(p, kinds, tokens)))
    )
    pattern.get("firstChild").foreach(p => errors ++= patternErrors(p, kinds, tokens))
    errors.result()
  }

  case class Result(id: String, errors: List[String]) {
    def covered: Boolean = errors.isEmpty
  }

  def evaluate(root: Path): List[Result] = {
    val catalog = Json.parseFile(root.resolve("ast/syntax-variants.json"))
    val schema = Json.parseFile(root.resolve("schemas/syntax-variants.schema.json"))
    val validation = new SchemaValidator.Errors
    SchemaValidator.check(catalog, schema, schema, "$", validation)
    require(validation.isEmpty, validation.toList.mkString("; "))
    val pin = Json.parseFile(root.resolve("pin.json"))
    require(catalog("upstreamCommit") == pin("upstream")("commit"), "syntax catalogue upstreamCommit differs from pin")
    require(catalog("upstreamTag") == pin("upstream")("tag"), "syntax catalogue upstreamTag differs from pin")
    val kinds = Json.parseFile(root.resolve("ast/treekind.json"))("kinds").asArray.map(_("name").asString).toSet
    val tokens = Json.parseFile(root.resolve("ast/tokenkind.json"))("kinds").asArray.map(_("name").asString).toSet
    val variants = catalog("variants").asArray
    require(variants.nonEmpty, "syntax catalogue is empty")
    val ids = variants.map(_("id").asString)
    require(ids.distinct.size == ids.size, "duplicate syntax variant id")
    variants.map { variant =>
      val errors = List.newBuilder[String]
      variant("evidence").asArray.foreach { evidence =>
        val fixture = evidence("fixture").asString
        val source = root.resolve(fixture).normalize()
        require(source.startsWith(root.toAbsolutePath.normalize()), s"fixture escapes root: $fixture")
        val form = evidence("form").asString
        val basename = source.getFileName.toString.stripSuffix(".flix")
        val projection = root.resolve(s"fixtures/${if (form == "raw") "raw" else "expected"}/$basename.json")
        val patterns = evidence("matches").asArray
        val absent = evidence("absent").asArray
        (patterns ++ absent).foreach(p => errors ++= patternErrors(p, kinds, tokens).map(s"$fixture: " + _))
        if (!Files.isRegularFile(source)) errors += s"missing source fixture: $fixture"
        if (!Files.isRegularFile(projection)) errors += s"missing $form projection: $fixture"
        else {
          val doc = Json.parseFile(projection)
          if (
            doc("upstreamCommit") != pin("upstream")("commit") || doc("oracleSha256") != pin("oracleArtifact")("sha256")
          )
            errors += s"projection provenance mismatch: $fixture"
          if (doc("form").asString != form) errors += s"wrong projection form: $fixture"
          val units = doc("units").asArray.filter(_("source").asString == fixture)
          if (units.size != 1) errors += s"expected one source unit: $fixture"
          else {
            val unit = units.head
            def diagnosticKeys(xs: List[Json]): List[(String, Int)] =
              xs.map(d => d("kind").asString -> d("line").asInt).sortBy(identity)
            if (diagnosticKeys(unit("diagnostics").asArray) != diagnosticKeys(evidence("diagnostics").asArray))
              errors += s"diagnostic kind/line mismatch: $fixture"
            val all = nodes(unit("tree"))
            patterns.zipWithIndex.foreach { case (p, i) =>
              if (!all.exists(matches(_, p))) errors += s"missing predicate $i: $fixture"
            }
            absent.zipWithIndex.foreach { case (p, i) =>
              if (all.exists(matches(_, p))) errors += s"forbidden predicate $i: $fixture"
            }
          }
        }
      }
      Result(variant("id").asString, errors.result())
    }
  }

  def main(args: Array[String]): Unit = {
    require(args.isEmpty, "SyntaxCoverage takes no arguments; run from repository root")
    val results = evaluate(Paths.get("").toAbsolutePath.normalize())
    val missing = results.count(!_.covered)
    val rows = results
      .map { r =>
        val errors = r.errors.map(e => "\"" + Json.esc(e) + "\"").mkString(",")
        s"""{"id":"${Json.esc(r.id)}","status":"${if (r.covered) "covered" else "missing"}","errors":[$errors]}"""
      }
      .mkString(",\n    ")
    println(
      s"""{"schemaVersion":1,"scope":"bounded catalogue","covered":${results.size - missing},"missing":$missing,"variants":[
    $rows
]}"""
    )
    require(missing == 0, "syntax variant evidence is missing or contradicted")
  }
}
