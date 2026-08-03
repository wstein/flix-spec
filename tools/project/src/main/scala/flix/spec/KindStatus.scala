package flix.spec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** Generates `ast/status.json`: one evidence-backed status per `TreeKind` and per `TokenKind`.
  *
  * [[Coverage]] deliberately refuses to editorialise about the kinds fixtures do not exercise, because it cannot: from
  * inside the fixture suite, "no fixture reaches this" and "nothing can reach this" look identical. [[ReachabilityRun]]
  * supplies the second opinion — what the reference actually emits across the whole pinned corpus — but a corpus is
  * also just a sample, so *it* cannot tell an unexercised kind from an unreachable one either.
  *
  * Neither artifact alone can justify the sentence people actually want to read, so this joins them and names the
  * remainder honestly:
  *
  *   - `reachable-covered` — the reference emits it somewhere in the corpus, and a fixture pins it. The strong case.
  *   - `fixture-only` — a fixture pins it but 873 files of real Flix never produce it. This is the case *for* curating
  *     fixtures at all, and it is emphatically not "dead syntax".
  *   - `corpus-only` — real Flix produces it and no fixture pins it. A genuine gap in this suite, and the only status
  *     that is a to-do item.
  *   - `structurally-unattachable` — it cannot appear in any tree from any input. This is a claim about the reference
  *     parser, so it may not be inferred from absence; it must be argued with source citations in
  *     `ast/unattachable.json`, and this generator refuses an entry that measurement contradicts.
  *   - `unknown` — no fixture, no corpus occurrence, no argument. Not a gap and not a sentinel: unexplained.
  *
  * The distinction matters because the honest headline is a ratio over what the reference can actually produce, not
  * over the raw inventory. Reporting "184 of 192" invites the reader to treat eight kinds as missing when six of them
  * are unreachable by construction and two more are pinned by fixtures the corpus happens not to contain.
  */
object KindStatus {

  val ToolVersion = "1.0.0"

  private val ReachableCovered = "reachable-covered"
  private val FixtureOnly = "fixture-only"
  private val CorpusOnly = "corpus-only"
  private val Unattachable = "structurally-unattachable"
  private val Unknown = "unknown"

  /** Every status, in the order they are reported. */
  private val Statuses = List(ReachableCovered, FixtureOnly, CorpusOnly, Unattachable, Unknown)

  private def obj(sb: StringBuilder, name: String, entries: List[(String, String)], last: Boolean = false): Unit = {
    val tail = if (last) "\n" else ",\n"
    sb.append(s"""  "$name": {""")
    if (entries.isEmpty) sb.append(s"}$tail")
    else {
      sb.append("\n")
      entries.zipWithIndex.foreach { case ((k, v), i) =>
        val comma = if (i < entries.length - 1) "," else ""
        sb.append(s"""    "$k": "$v"$comma\n""")
      }
      sb.append(s"  }$tail")
    }
  }

  private def tally(sb: StringBuilder, name: String, total: Int, statuses: Map[String, String]): Unit = {
    sb.append(s"""  "$name": {\n""")
    sb.append(s"""    "total": $total,\n""")
    Statuses.zipWithIndex.foreach { case (s, i) =>
      val comma = if (i < Statuses.length - 1) "," else ""
      sb.append(s"""    "$s": ${statuses.count(_._2 == s)}$comma\n""")
    }
    sb.append("  },\n")
  }

  /** Reads one side of `ast/unattachable.json`, checking each entry against the inventory and against measurement. */
  private def evidence(
      doc: Json,
      field: String,
      inventory: Set[String],
      covered: Set[String],
      reachable: Set[String]
  ): Set[String] = {
    val entries = doc(field).asArray.map(_("name").asString)
    val fatal = scala.collection.mutable.ListBuffer.empty[String]

    entries.diff(inventory.toList).foreach(n => fatal += s"$field entry '$n' is not in the inventory")
    entries.diff(entries.distinct).distinct.foreach(n => fatal += s"$field entry '$n' is listed twice")
    if (entries != entries.sorted) fatal += s"$field entries are not sorted by name"

    // The falsification check. An entry claims a kind cannot appear in any tree; a fixture containing it, or a corpus
    // file producing it, is a direct counter-example. Refusing to write the file is the point -- an evidence list that
    // silently loses to measurement would be worse than no evidence list at all.
    entries
      .filter(covered)
      .foreach(n => fatal += s"$field entry '$n' is claimed unattachable but a fixture contains it")
    entries
      .filter(reachable)
      .foreach(n => fatal += s"$field entry '$n' is claimed unattachable but the corpus emits it")

    if (fatal.nonEmpty) {
      System.err.println("FATAL: ast/unattachable.json is contradicted by measurement")
      fatal.foreach(e => System.err.println(s"  $e"))
      sys.exit(1)
    }
    entries.toSet
  }

  private def classify(
      inventory: List[String],
      covered: Set[String],
      reachable: Set[String],
      unattachable: Set[String]
  ): Map[String, String] =
    inventory.map { k =>
      val status =
        if (covered(k) && reachable(k)) ReachableCovered
        else if (covered(k)) FixtureOnly
        else if (reachable(k)) CorpusOnly
        else if (unattachable(k)) Unattachable
        else Unknown
      k -> status
    }.toMap

  def main(args: Array[String]): Unit = {
    val pin = Json.parseFile(Paths.get("pin.json"))
    val treeInv = Json.parseFile(Paths.get("ast/treekind.json"))
    val tokInv = Json.parseFile(Paths.get("ast/tokenkind.json"))
    val coverage = Json.parseFile(Paths.get("ast/coverage.json"))
    val reach = Json.parseFile(Paths.get("ast/reachability.json"))
    val unattach = Json.parseFile(Paths.get("ast/unattachable.json"))
    val schema = Json.parseFile(Paths.get("schemas/unattachable.schema.json"))

    val errors = new SchemaValidator.Errors
    SchemaValidator.check(unattach, schema, schema, "unattachable.json", errors)
    if (!errors.isEmpty) {
      System.err.println("FATAL: ast/unattachable.json does not conform to schemas/unattachable.schema.json")
      errors.toList.foreach(e => System.err.println(s"  $e"))
      sys.exit(1)
    }

    // Every input must describe the same pin. coverage.json and reachability.json are regenerated by different jobs
    // (verify vs. the weekly corpus run), so a stale one is a real possibility rather than a hypothetical, and joining
    // across pins would produce a confidently wrong status for every kind.
    val upstreamCommit = pin("upstream")("commit").asString
    List(
      "ast/treekind.json" -> treeInv,
      "ast/tokenkind.json" -> tokInv,
      "ast/coverage.json" -> coverage,
      "ast/reachability.json" -> reach,
      "ast/unattachable.json" -> unattach
    ).foreach { case (path, doc) =>
      val got = doc("upstreamCommit").asString
      if (got != upstreamCommit) {
        System.err.println(s"FATAL: $path is at upstreamCommit $got, but pin.json is at $upstreamCommit.")
        System.err.println("  Regenerate it, or -- for ast/unattachable.json -- re-read every citation against the")
        System.err.println("  new source before restamping it. Line numbers do not survive a pin bump on trust.")
        sys.exit(1)
      }
    }

    val treeKinds = treeInv("kinds").asArray.map(_("name").asString)
    val tokenKinds = tokInv("kinds").asArray.map(_("name").asString)

    val treeCovered = coverage("covered").asObject.keySet
    val tokCovered = coverage("tokenCovered").asObject.keySet
    val treeReachable = reach("reachable").asObject.keySet
    val tokReachable = reach("tokenReachable").asObject.keySet

    val treeUnattach = evidence(unattach, "treeKinds", treeKinds.toSet, treeCovered, treeReachable)
    val tokUnattach = evidence(unattach, "tokenKinds", tokenKinds.toSet, tokCovered, tokReachable)

    val treeStatus = classify(treeKinds, treeCovered, treeReachable, treeUnattach)
    val tokStatus = classify(tokenKinds, tokCovered, tokReachable, tokUnattach)

    val sb = new StringBuilder
    sb.append("{\n")
    sb.append("  \"schemaVersion\": 1,\n")
    sb.append("  \"generatedBy\": \"flix.spec.KindStatus\",\n")
    sb.append(s"""  "toolVersion": "$ToolVersion",\n""")
    sb.append(s"""  "upstreamCommit": "$upstreamCommit",\n""")
    sb.append(s"""  "oracleSha256": "${treeInv("oracleSha256").asString}",\n""")
    sb.append(s"""  "fixtureCount": ${coverage("fixtureCount").asInt},\n""")
    sb.append(s"""  "corpusFiles": ${reach("corpusFiles").asInt},\n""")
    tally(sb, "treeKind", treeKinds.length, treeStatus)
    tally(sb, "tokenKind", tokenKinds.length, tokStatus)
    obj(sb, "treeKindStatus", treeKinds.sorted.map(k => k -> treeStatus(k)))
    obj(sb, "tokenKindStatus", tokenKinds.sorted.map(k => k -> tokStatus(k)), last = true)
    sb.append("}\n")

    Files.writeString(Paths.get("ast/status.json"), sb.toString, StandardCharsets.UTF_8)

    def summarise(label: String, total: Int, st: Map[String, String]): String =
      s"  $label ${"%3d".format(total)} = " + Statuses.map(s => s"${st.count(_._2 == s)} $s").mkString(", ")

    println(
      "Wrote ast/status.json\n" +
        summarise("TreeKind ", treeKinds.length, treeStatus) + "\n" +
        summarise("TokenKind", tokenKinds.length, tokStatus)
    )

    // `unknown` is the one status nobody can act on: it means the suite has neither exercised the kind nor explained
    // why it cannot. It is not failed here -- a pin bump can legitimately introduce one, and failing the build would
    // pressure the next person to write an unargued entry in unattachable.json just to get green.
    val unknowns = (treeStatus ++ tokStatus).filter(_._2 == Unknown).keys.toList.sorted
    if (unknowns.nonEmpty)
      println(s"NOTE: ${unknowns.length} kind(s) neither exercised nor explained: ${unknowns.mkString(", ")}")
  }
}
