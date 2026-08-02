package flix.spec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._

/** Generates `ast/coverage.json`: which `TreeKind`s the fixture suite exercises, replacing the former
  * `tools/project/coverage.py`. Run from the repository root.
  *
  * Implementation plan section 7, Phase 2: fixture coverage must be a measurable artifact rather than a claim. A kind
  * is *covered* when at least one committed expectation contains a node of that kind.
  *
  * An uncovered kind is not automatically a gap. Some kinds are only reachable from inputs no fixture yet exercises;
  * others may be unreachable from any input at all, which is a fact about Flix rather than about this suite.
  * Distinguishing the two needs the corpus-wide reachability run ([[ReachabilityRun]]), so this deliberately reports
  * coverage and does not editorialise about the remainder.
  */
object Coverage {

  private def walk(
      node: Json,
      counts: scala.collection.mutable.Map[String, Int],
      wrappers: scala.collection.mutable.Map[String, Int]
  ): Unit = {
    node.get("kind") match {
      case None => // token leaf
      case Some(k) =>
        val kind = k.asString
        counts(kind) = counts.getOrElse(kind, 0) + 1
        val children = node.get("children").map(_.asArray).getOrElse(Nil)
        // A node whose only child is another node carries no structure of its own. Consumers that
        // were not the reference's parent routinely omit these, so the projection map's `elide`
        // list exists to skip them (docs/CONFORMANCE.md). Counting them here keeps that
        // justification a generated figure rather than a hand-written one that goes stale the
        // next time a fixture is added.
        if (children.length == 1 && children.head.get("kind").isDefined)
          wrappers(kind) = wrappers.getOrElse(kind, 0) + 1
        children.foreach(walk(_, counts, wrappers))
    }
  }

  def main(args: Array[String]): Unit = {
    val inventory = Json.parseFile(Paths.get("ast/treekind.json"))
    val allKinds = inventory("kinds").asArray.map(_("name").asString)

    val expectedDir = Paths.get("fixtures/expected")
    val fixtures =
      Files.list(expectedDir).iterator().asScala.map(_.toString).filter(_.endsWith(".json")).toList.sorted

    if (fixtures.isEmpty) {
      System.err.println("FATAL: no expectations in fixtures/expected/")
      sys.exit(1)
    }

    val counts = scala.collection.mutable.Map.empty[String, Int]
    val wrappers = scala.collection.mutable.Map.empty[String, Int]
    fixtures.foreach { f =>
      val doc = Json.parseFile(Paths.get(f))
      doc("units").asArray.foreach(unit => walk(unit("tree"), counts, wrappers))
    }

    val allKindsSet = allKinds.toSet
    val unknown = counts.keySet.diff(allKindsSet).toList.sorted
    if (unknown.nonEmpty) {
      System.err.println(s"FATAL: kinds not in inventory: $unknown")
      sys.exit(1)
    }

    val covered = allKinds.filter(k => counts.getOrElse(k, 0) > 0).sorted
    val uncovered = allKinds.filter(k => counts.getOrElse(k, 0) == 0).sorted

    val sb = new StringBuilder
    sb.append("{\n")
    sb.append("  \"schemaVersion\": 1,\n")
    sb.append("  \"generatedBy\": \"flix.spec.Coverage\",\n")
    sb.append(s"""  "upstreamCommit": "${inventory("upstreamCommit").asString}",\n""")
    sb.append(s"""  "oracleSha256": "${inventory("oracleSha256").asString}",\n""")
    sb.append(s"  \"treeKindCount\": ${allKinds.length},\n")
    sb.append(s"  \"coveredCount\": ${covered.length},\n")
    sb.append(s"  \"uncoveredCount\": ${uncovered.length},\n")
    sb.append(s"  \"fixtureCount\": ${fixtures.length},\n")

    // Always-a-wrapper kinds: every occurrence in the suite has exactly one child node. These are
    // the ones an `elide` list can safely name.
    val alwaysWrapper = allKinds.filter(k => counts.getOrElse(k, 0) > 0 && wrappers.getOrElse(k, 0) == counts(k)).sorted
    val totalNodes = counts.values.sum
    val wrapperNodes = alwaysWrapper.map(counts).sum
    sb.append(s"  \"nodeCount\": $totalNodes,\n")
    sb.append(s"  \"singleChildWrapperNodes\": $wrapperNodes,\n")
    sb.append("  \"alwaysSingleChildWrapper\": {")
    if (alwaysWrapper.isEmpty) sb.append("},\n")
    else {
      sb.append("\n")
      alwaysWrapper.zipWithIndex.foreach { case (k, i) =>
        val comma = if (i < alwaysWrapper.length - 1) "," else ""
        sb.append(s"""    "$k": ${counts(k)}$comma\n""")
      }
      sb.append("  },\n")
    }
    sb.append("  \"covered\": {")
    if (covered.isEmpty) sb.append("},\n")
    else {
      sb.append("\n")
      covered.zipWithIndex.foreach { case (k, i) =>
        val comma = if (i < covered.length - 1) "," else ""
        sb.append(s"""    "$k": ${counts(k)}$comma\n""")
      }
      sb.append("  },\n")
    }
    sb.append("  \"uncovered\": [")
    if (uncovered.isEmpty) sb.append("]\n")
    else {
      sb.append("\n")
      uncovered.zipWithIndex.foreach { case (k, i) =>
        val comma = if (i < uncovered.length - 1) "," else ""
        sb.append(s"""    "$k"$comma\n""")
      }
      sb.append("  ]\n")
    }
    sb.append("}\n")

    Files.writeString(Paths.get("ast/coverage.json"), sb.toString, StandardCharsets.UTF_8)

    val pct = 100.0 * covered.length / allKinds.length
    val wrapPct = 100.0 * wrapperNodes / totalNodes
    println(
      f"Wrote ast/coverage.json: ${covered.length}/${allKinds.length} kinds covered ($pct%.1f%%) " +
        s"by ${fixtures.length} fixtures; ${uncovered.length} uncovered; " +
        f"$wrapperNodes/$totalNodes nodes ($wrapPct%.1f%%) are single-child wrappers"
    )
  }
}
