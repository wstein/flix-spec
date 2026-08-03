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

  /** Appends a `"name": {"k": n, ...}` member, matching [[ReachabilityRun]]'s writers byte for byte. */
  private def obj(sb: StringBuilder, name: String, entries: List[(String, Int)], last: Boolean = false): Unit = {
    val tail = if (last) "\n" else ",\n"
    sb.append(s"""  "$name": {""")
    if (entries.isEmpty) sb.append(s"}$tail")
    else {
      sb.append("\n")
      entries.zipWithIndex.foreach { case ((k, n), i) =>
        val comma = if (i < entries.length - 1) "," else ""
        sb.append(s"""    "$k": $n$comma\n""")
      }
      sb.append(s"  }$tail")
    }
  }

  /** Appends a `"name": ["a", ...]` member. */
  private def arr(sb: StringBuilder, name: String, items: List[String], last: Boolean = false): Unit = {
    val tail = if (last) "\n" else ",\n"
    sb.append(s"""  "$name": [""")
    if (items.isEmpty) sb.append(s"]$tail")
    else {
      sb.append("\n")
      items.zipWithIndex.foreach { case (k, i) =>
        val comma = if (i < items.length - 1) "," else ""
        sb.append(s"""    "$k"$comma\n""")
      }
      sb.append(s"  ]$tail")
    }
  }

  private def walk(
      node: Json,
      counts: scala.collection.mutable.Map[String, Int],
      wrappers: scala.collection.mutable.Map[String, Int],
      tokens: scala.collection.mutable.Map[String, Int]
  ): Unit = {
    node.get("kind") match {
      // A token leaf. Counting these makes lexical coverage a generated figure on the same footing
      // as tree coverage: `ast/tokenkind.json` is the whole contract for consumers that have no
      // parse tree, so "which tokens does the suite actually exercise" cannot be a prose claim.
      case None => node.get("token").foreach(t => tokens(t.asString) = tokens.getOrElse(t.asString, 0) + 1)
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
        children.foreach(walk(_, counts, wrappers, tokens))
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

    val tokenInventory = Json.parseFile(Paths.get("ast/tokenkind.json"))
    val allTokens = tokenInventory("kinds").asArray.map(_("name").asString)

    val counts = scala.collection.mutable.Map.empty[String, Int]
    val wrappers = scala.collection.mutable.Map.empty[String, Int]
    val tokens = scala.collection.mutable.Map.empty[String, Int]
    fixtures.foreach { f =>
      val doc = Json.parseFile(Paths.get(f))
      doc("units").asArray.foreach(unit => walk(unit("tree"), counts, wrappers, tokens))
    }

    val allKindsSet = allKinds.toSet
    val unknown = counts.keySet.diff(allKindsSet).toList.sorted
    if (unknown.nonEmpty) {
      System.err.println(s"FATAL: kinds not in inventory: $unknown")
      sys.exit(1)
    }

    val unknownTokens = tokens.keySet.diff(allTokens.toSet).toList.sorted
    if (unknownTokens.nonEmpty) {
      System.err.println(s"FATAL: tokens not in inventory: $unknownTokens")
      sys.exit(1)
    }

    val covered = allKinds.filter(k => counts.getOrElse(k, 0) > 0).sorted
    val uncovered = allKinds.filter(k => counts.getOrElse(k, 0) == 0).sorted
    val tokCovered = allTokens.filter(t => tokens.getOrElse(t, 0) > 0).sorted
    val tokUncovered = allTokens.filter(t => tokens.getOrElse(t, 0) == 0).sorted

    val sb = new StringBuilder
    sb.append("{\n")
    // 2: added the tokenKind* fields. Additive, but consumers are told to gate on schemaVersion
    // (docs/VERSIONING.md), so it is a bump rather than a silent widening.
    sb.append("  \"schemaVersion\": 2,\n")
    sb.append("  \"generatedBy\": \"flix.spec.Coverage\",\n")
    sb.append(s"""  "upstreamCommit": "${inventory("upstreamCommit").asString}",\n""")
    sb.append(s"""  "oracleSha256": "${inventory("oracleSha256").asString}",\n""")
    sb.append(s"  \"treeKindCount\": ${allKinds.length},\n")
    sb.append(s"  \"coveredCount\": ${covered.length},\n")
    sb.append(s"  \"uncoveredCount\": ${uncovered.length},\n")
    sb.append(s"  \"fixtureCount\": ${fixtures.length},\n")
    sb.append(s"  \"tokenKindCount\": ${allTokens.length},\n")
    sb.append(s"  \"tokenCoveredCount\": ${tokCovered.length},\n")
    sb.append(s"  \"tokenUncoveredCount\": ${tokUncovered.length},\n")

    // Always-a-wrapper kinds: every occurrence in the suite has exactly one child node. These are
    // the ones an `elide` list can safely name.
    val alwaysWrapper = allKinds.filter(k => counts.getOrElse(k, 0) > 0 && wrappers.getOrElse(k, 0) == counts(k)).sorted
    val totalNodes = counts.values.sum
    val wrapperNodes = alwaysWrapper.map(counts).sum
    sb.append(s"  \"nodeCount\": $totalNodes,\n")
    sb.append(s"  \"singleChildWrapperNodes\": $wrapperNodes,\n")
    obj(sb, "alwaysSingleChildWrapper", alwaysWrapper.map(k => k -> counts(k)))
    obj(sb, "covered", covered.map(k => k -> counts(k)))
    arr(sb, "uncovered", uncovered)
    obj(sb, "tokenCovered", tokCovered.map(t => t -> tokens(t)))
    arr(sb, "tokenUncovered", tokUncovered, last = true)
    sb.append("}\n")

    Files.writeString(Paths.get("ast/coverage.json"), sb.toString, StandardCharsets.UTF_8)

    val pct = 100.0 * covered.length / allKinds.length
    val wrapPct = 100.0 * wrapperNodes / totalNodes
    println(
      f"Wrote ast/coverage.json: ${covered.length}/${allKinds.length} kinds covered ($pct%.1f%%) " +
        s"by ${fixtures.length} fixtures; ${uncovered.length} uncovered; " +
        f"$wrapperNodes/$totalNodes nodes ($wrapPct%.1f%%) are single-child wrappers; " +
        s"${tokCovered.length}/${allTokens.length} tokens covered"
    )
  }
}
