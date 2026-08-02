package flix.spec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._

/** Compares a consumer parser's projected trees against `fixtures/expected/`, replacing the former
  * `tools/project/conformance.py`. This is the shared half of the conformance check (implementation plan section 7,
  * Phase 3): each consumer produces canonical projected trees for the fixtures, and the comparison lives here so four
  * repositories do not re-derive it four times.
  *
  * What is compared, per `docs/PROJECTION.md` section 3:
  *
  *   - node kind, child order and nesting are load-bearing and gated;
  *   - spans and tokens are not compared -- token vocabularies differ legitimately between parsers, and spans are
  *     advisory, so comparing either would report differences that are not disagreements about structure.
  *
  * Usage: `conformance --actual <dir> [--map <file>] [--report <file>] [--baseline <n>]`. Exit status is non-zero when
  * divergences exceed `--baseline` (default 0), so the ratchet is the gate. Run from the repository root.
  */
object Conformance {

  private val ExpectedDir = "fixtures/expected"
  private val MaxDivergencesPerFixture = 20

  final case class KTree(kind: String, children: List[KTree])

  private def kindTree(node: Json): Option[KTree] =
    node.get("kind") match {
      case None => None // token leaf
      case Some(k) =>
        val children = node.get("children").map(_.asArray).getOrElse(Nil).flatMap(kindTree)
        Some(KTree(k.asString, children))
    }

  private def loadUnits(path: String): Map[String, KTree] =
    Json
      .parseFile(Paths.get(path))
      .get("units")
      .map(_.asArray)
      .getOrElse(Nil)
      .map(u => u("source").asString -> kindTree(u("tree")).get)
      .toMap

  final class Stats {
    val counts = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    val unmappedNames = scala.collection.mutable.Set.empty[String]
    def inc(key: String): Unit = counts(key) += 1
  }

  final case class Divergence(path: String, expected: String, actual: String, reason: String)

  /** Removes transparent nodes from a child list. Used on both sides: `elide` names canonical wrappers the consumer
    * does not produce, `ignored` names the consumer's own wrappers with no counterpart in the reference. A transparent
    * node is dropped when empty and replaced by its child when it has exactly one; a node with two or more children is
    * kept, since splicing its children into the parent would discard real structure.
    */
  private def applyElision(children: List[KTree], elide: Set[String], stats: Stats, counter: String): List[KTree] = {
    val out = List.newBuilder[KTree]
    children.foreach { start =>
      var current = Option(start)
      var looping = true
      while (looping) {
        current match {
          case Some(KTree(kind, kids)) if elide.contains(kind) && kids.length <= 1 =>
            stats.inc(counter)
            current = kids.headOption
            if (current.isEmpty) looping = false
          case _ => looping = false
        }
      }
      current.foreach(out += _)
    }
    out.result()
  }

  /** Walks both trees in lockstep, appending divergences to `out`. */
  private def compare(
      expected: KTree,
      actual: KTree,
      mapping: Option[Map[String, String]],
      ignored: Set[String],
      elide: Set[String],
      path: String,
      out: scala.collection.mutable.Buffer[Divergence],
      stats: Stats
  ): Unit = {
    if (out.length >= MaxDivergencesPerFixture) return

    val actKind = mapping match {
      case None => actual.kind
      case Some(m) if m.contains(actual.kind) =>
        stats.inc("mapped")
        m(actual.kind)
      case Some(_) =>
        stats.inc("unmapped")
        stats.unmappedNames += actual.kind
        return // not a disagreement: we simply have no opinion yet
    }

    val expChildren = applyElision(expected.children, elide, stats, "elided")
    val actChildren = applyElision(actual.children, ignored, stats, "ignored")

    stats.inc("compared")
    if (expected.kind != actKind) {
      out += Divergence(path, expected.kind, actKind, "kind")
      return // subtree shape is meaningless once the kinds disagree
    }

    if (expChildren.length != actChildren.length)
      out += Divergence(path, s"${expChildren.length} children", s"${actChildren.length} children", "arity")

    expChildren.zip(actChildren).zipWithIndex.foreach { case ((e, a), i) =>
      compare(e, a, mapping, ignored, elide, s"$path.${expected.kind}[$i]", out, stats)
    }
  }

  private def esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
  private def jsonStringArray(items: Seq[String], indent: String): String =
    if (items.isEmpty) "[]"
    else "[\n" + items.map(i => s"""$indent  "${esc(i)}"""").mkString(",\n") + s"\n$indent]"

  final case class Args(
      actual: String,
      map: Option[String],
      report: Option[String],
      baseline: Int
  )

  private def parseArgs(argv: Array[String]): Args = {
    var actual: Option[String] = None
    var map: Option[String] = None
    var report: Option[String] = None
    var baseline = 0
    var i = 0
    while (i < argv.length) {
      argv(i) match {
        case "--actual"   => actual = Some(argv(i + 1)); i += 2
        case "--map"      => map = Some(argv(i + 1)); i += 2
        case "--report"   => report = Some(argv(i + 1)); i += 2
        case "--baseline" => baseline = argv(i + 1).toInt; i += 2
        case other =>
          System.err.println(s"unknown argument: $other")
          sys.exit(2)
      }
    }
    Args(
      actual.getOrElse {
        System.err.println("usage: Conformance --actual <dir> [--map <file>] [--report <file>] [--baseline <n>]")
        sys.exit(2)
      },
      map,
      report,
      baseline
    )
  }

  def main(argv: Array[String]): Unit = {
    val args = parseArgs(argv)

    var mapping: Option[Map[String, String]] = None
    var ignored: Set[String] = Set.empty
    var elide: Set[String] = Set.empty
    var consumer = Paths.get(args.actual.stripSuffix("/")).getFileName.toString

    args.map.foreach { mapFile =>
      val m = Json.parseFile(Paths.get(mapFile))
      val mappings = m("mappings").asObject.view.mapValues(_.asString).toMap
      mapping = Some(mappings)
      ignored = m.get("ignored").map(_.asArray.map(_.asString).toSet).getOrElse(Set.empty)
      elide = m.get("elide").map(_.asArray.map(_.asString).toSet).getOrElse(Set.empty)
      consumer = m("consumer").asString

      val inventory = Json.parseFile(Paths.get("ast/treekind.json"))("kinds").asArray.map(_("name").asString).toSet
      val bad = mappings.values.toSet.diff(inventory).toList.sorted
      if (bad.nonEmpty) {
        System.err.println(s"FATAL: projection map targets kinds absent from the inventory: $bad")
        sys.exit(1)
      }
    }

    val expectedFiles =
      Files.list(Paths.get(ExpectedDir)).iterator().asScala.map(_.toString).filter(_.endsWith(".json")).toList.sorted
    if (expectedFiles.isEmpty) {
      System.err.println(s"FATAL: no expectations in $ExpectedDir/")
      sys.exit(1)
    }

    val stats = new Stats
    val divergences = scala.collection.mutable.Buffer.empty[(String, Divergence)]
    var agreeing = 0
    val missing = scala.collection.mutable.Buffer.empty[String]

    expectedFiles.foreach { ef =>
      val name = Paths.get(ef).getFileName.toString
      val af = Paths.get(args.actual, name).toString
      if (!Files.exists(Paths.get(af))) {
        missing += name
      } else {
        val expUnits = loadUnits(ef)
        val actUnits = loadUnits(af)
        val found = scala.collection.mutable.Buffer.empty[Divergence]
        expUnits.foreach { case (source, expTree) =>
          actUnits.get(source).orElse(actUnits.values.headOption) match {
            case None          => found += Divergence(source, "tree", "nothing", "missing-unit")
            case Some(actTree) => compare(expTree, actTree, mapping, ignored, elide, source, found, stats)
          }
        }
        if (found.nonEmpty) divergences ++= found.map(name -> _)
        else agreeing += 1
      }
    }

    val fixturesCompared = expectedFiles.length - missing.length
    val divergenceList = divergences.toList

    args.report.foreach { reportPath =>
      val p = Paths.get(reportPath)
      Option(p.getParent).foreach(Files.createDirectories(_))
      val sb = new StringBuilder
      sb.append("{\n")
      sb.append("  \"schemaVersion\": 1,\n")
      sb.append("  \"generatedBy\": \"flix.spec.Conformance\",\n")
      sb.append(s"""  "consumer": "${esc(consumer)}",\n""")
      sb.append(s"  \"fixturesExpected\": ${expectedFiles.length},\n")
      sb.append(s"  \"fixturesCompared\": $fixturesCompared,\n")
      sb.append(s"""  "fixturesMissing": ${jsonStringArray(missing.toList.sorted, "  ")},\n""")
      sb.append(s"  \"fixturesAgreeing\": $agreeing,\n")
      sb.append(s"  \"nodesCompared\": ${stats.counts("compared")},\n")
      sb.append(s"  \"nodesMapped\": ${stats.counts("mapped")},\n")
      sb.append(s"  \"nodesIgnored\": ${stats.counts("ignored")},\n")
      sb.append(s"  \"nodesElided\": ${stats.counts("elided")},\n")
      sb.append(s"  \"nodesUnmapped\": ${stats.counts("unmapped")},\n")
      sb.append(s"""  "unmappedNames": ${jsonStringArray(stats.unmappedNames.toList.sorted, "  ")},\n""")
      sb.append(s"  \"divergenceCount\": ${divergenceList.length},\n")
      sb.append("  \"divergences\": [")
      val capped = divergenceList.take(200)
      if (capped.isEmpty) sb.append("]\n")
      else {
        sb.append("\n")
        capped.zipWithIndex.foreach { case ((fixture, d), i) =>
          val comma = if (i < capped.length - 1) "," else ""
          sb.append(
            s"""    {"fixture": "${esc(fixture)}", "path": "${esc(d.path)}", "expected": "${esc(d.expected)}", """
          )
          sb.append(s""""actual": "${esc(d.actual)}", "reason": "${esc(d.reason)}"}$comma\n""")
        }
        sb.append("  ]\n")
      }
      sb.append("}\n")
      Files.writeString(p, sb.toString, StandardCharsets.UTF_8)
    }

    val unmappedSuffix = if (stats.counts("unmapped") > 0) s", ${stats.counts("unmapped")} unmapped" else ""
    println(
      s"$consumer: $agreeing/$fixturesCompared fixtures agree, " +
        s"${divergenceList.length} divergences, ${stats.counts("compared")} nodes compared$unmappedSuffix"
    )
    if (missing.nonEmpty) System.err.println(s"  ${missing.length} fixture(s) had no consumer output")

    if (divergenceList.length > args.baseline) {
      System.err.println(s"FATAL: ${divergenceList.length} divergences exceeds baseline ${args.baseline}")
      divergenceList.take(10).foreach { case (fixture, d) =>
        System.err.println(s"  $fixture ${d.path}: expected '${d.expected}', got '${d.actual}' (${d.reason})")
      }
      sys.exit(1)
    }
  }
}
