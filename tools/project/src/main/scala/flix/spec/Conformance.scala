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
  * The report it writes has **two lanes, never summed into one score**:
  *
  *   - `oracle_conformance` -- this comparison. Its expectations come from the pinned reference, so it inherits the
  *     reference's defects by construction and measures *compatibility*, not correctness. A consumer reproducing a
  *     compiler bug scores as agreeing; one implementing the reference's intent instead scores as divergent.
  *   - `source_invariants` -- [[SourceInvariants]], which consults no expected tree and can therefore contradict the
  *     reference. A consumer can pass the first lane and fail the second, and that case is the reason for the split:
  *     blanking one token's text leaves kind, child order and nesting untouched, so every fixture still agrees while
  *     the output has demonstrably lost its input.
  *
  * Usage: `conformance --actual <dir> [--map <file>] [--report <file>] [--baseline <n>]`. Exit status is non-zero when
  * divergences exceed `--baseline` (default 0) **or** the source-invariants lane fails. The baseline is a ratchet for
  * mapping coverage, which is closed incrementally; it does not apply to the second lane, because losing a token is not
  * a gap someone is partway through closing. Run from the repository root.
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
  /** Splices a grouping node's children into its parent, at any arity.
    *
    * Stronger and more dangerous than elision, which only fires at arity <= 1. Flattening a node that carries real
    * structure would hide a genuine disagreement, so it is opt-in per node name and belongs only on pure grouping
    * constructs with no counterpart in the reference.
    *
    * Grammar-Kit needs it: `DECLARATION` wraps every declaration together with its doc, annotations and modifiers,
    * while the reference makes those direct children of `Decl.Def`. It appears 134 times across the fixtures, and
    * without flattening its whole subtree is never compared -- which is why comparison depth was 51%.
    */
  private def applyFlatten(children: List[KTree], flatten: Set[String], stats: Stats): List[KTree] =
    children.flatMap { c =>
      if (flatten.contains(c.kind)) {
        stats.inc("flattened")
        applyFlatten(c.children, flatten, stats)
      } else List(c)
    }

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
      flatten: Set[String],
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
    val actChildren =
      applyElision(applyFlatten(actual.children, flatten, stats), ignored, stats, "ignored")

    stats.inc("compared")
    if (expected.kind != actKind) {
      out += Divergence(path, expected.kind, actKind, "kind")
      return // subtree shape is meaningless once the kinds disagree
    }

    if (expChildren.length != actChildren.length)
      out += Divergence(path, s"${expChildren.length} children", s"${actChildren.length} children", "arity")

    expChildren.zip(actChildren).zipWithIndex.foreach { case ((e, a), i) =>
      compare(e, a, mapping, ignored, elide, flatten, s"$path.${expected.kind}[$i]", out, stats)
    }
  }

  /** Escapes a string for a JSON string literal.
    *
    * Control characters matter here and did not before. The flat report only ever embedded fixture names, kind names
    * and short reasons, none of which can contain a newline, so escaping quotes and backslashes was sufficient. The
    * source-invariants lane embeds diagnostic text -- a token-accounting failure carries a two-line before/after window
    * -- and a raw newline inside a JSON string is a parse error, not a formatting wrinkle. The first mutated consumer
    * output produced a report no JSON parser would read.
    */
  private def esc(s: String): String = {
    val sb = new StringBuilder(s.length)
    s.foreach {
      case '\\'          => sb.append("\\\\")
      case '"'           => sb.append("\\\"")
      case '\n'          => sb.append("\\n")
      case '\r'          => sb.append("\\r")
      case '\t'          => sb.append("\\t")
      case c if c < 0x20 => sb.append("\\u%04x".format(c.toInt))
      case c             => sb.append(c)
    }
    sb.toString
  }
  private def jsonStringArray(items: Seq[String], indent: String): String =
    if (items.isEmpty) "[]"
    else "[\n" + items.map(i => s"""$indent  "${esc(i)}"""").mkString(",\n") + s"\n$indent]"

  /** Identifies every input the verdicts were computed against.
    *
    * A conformance result is meaningless without it. This project has already seen a consumer depend on fixtures from
    * one Flix release while testing against a checkout of another -- a mismatch no naming convention detects, and one
    * that a report stating only "76/136 agree" cannot expose. Stamping the pin, the corpus, the fixture revision and
    * the vocabulary digests makes two reports comparable, or provably not comparable, without trusting a filename.
    *
    * `fixtureRevision` is computed here rather than read: fixtures are regenerated whenever the oracle or a fixture
    * source changes, and nothing else in the repository summarises the resulting set in one value.
    */
  private def provenance(expectedFiles: List[String]): List[(String, String)] = {
    val pin = Json.parseFile(Paths.get("pin.json"))
    val corpus = Json.parseFile(Paths.get("corpus/corpus.json"))
    val treeInv = Json.parseFile(Paths.get("ast/treekind.json"))
    val tokenInv = Json.parseFile(Paths.get("ast/tokenkind.json"))

    // Name and content of every expectation, so a renamed fixture moves the revision as surely as an edited one.
    val manifest = expectedFiles
      .map(f => s"${Paths.get(f).getFileName}:${TreeKindExtractor.fileDigest(Paths.get(f))}")
      .mkString("\n")

    List(
      "pinTag" -> pin("upstream")("tag").asString,
      "pinCommit" -> pin("upstream")("commit").asString,
      "oracleSha256" -> pin("oracleArtifact")("sha256").asString,
      "corpusTreeHash" -> corpus("upstream")("treeHash").asString,
      "fixtureRevision" -> TreeKindExtractor.sha256Hex(manifest.getBytes(StandardCharsets.UTF_8)),
      "treeKindDigest" -> treeInv("treeKindDigest").asString,
      "tokenKindDigest" -> tokenInv("tokenKindDigest").asString
    )
  }

  private def renderLaneChecks(checks: List[SourceInvariants.Check]): String =
    checks
      .map { c =>
        val failures = jsonStringArray(c.failures, "        ")
        s"""      {
           |        "id": "${esc(c.id)}",
           |        "verdict": "${esc(c.verdict)}",
           |        "claim": "${esc(c.claim)}",
           |        "checked": ${c.checked},
           |        "failed": ${c.failed},
           |        "detail": "${esc(c.detail)}",
           |        "failures": $failures
           |      }""".stripMargin
      }
      .mkString(",\n")

  private def renderReport(
      consumer: String,
      expectedFiles: List[String],
      fixturesCompared: Int,
      missing: List[String],
      agreeing: Int,
      stats: Stats,
      divergences: List[(String, Divergence)],
      baseline: Int,
      invariants: SourceInvariants.Lane
  ): String = {
    val sb = new StringBuilder
    sb.append("{\n")
    // 2: the flat body became two lanes and gained `provenance`. A consumer reading the old shape
    // finds none of its keys at the top level, which is the intended outcome -- silently keeping
    // them would let a reader take a compatibility number for a correctness one, the exact
    // conflation the split exists to end.
    sb.append("  \"schemaVersion\": 2,\n")
    sb.append("  \"generatedBy\": \"flix.spec.Conformance\",\n")
    sb.append(s"""  "consumer": "${esc(consumer)}",\n""")

    val prov = provenance(expectedFiles)
    sb.append("  \"provenance\": {\n")
    prov.zipWithIndex.foreach { case ((k, v), i) =>
      sb.append(s"""    "$k": "${esc(v)}"${if (i < prov.length - 1) "," else ""}\n""")
    }
    sb.append("  },\n")

    sb.append("  \"lanes\": {\n")

    val capped = divergences.take(200)
    sb.append("    \"oracle_conformance\": {\n")
    sb.append(s"""      "verdict": "${if (divergences.length > baseline) "fail" else "pass"}",\n""")
    sb.append(
      """      "claim": "agrees with the trees the pinned reference compiler produces",
        |      "authority": "derived",
        |      "caveat": "inherits the reference's defects by construction; agreeing with a compiler bug scores as agreement. See defects/ledger.json.",
        |""".stripMargin
    )
    sb.append(s"      \"baseline\": $baseline,\n")
    sb.append(s"      \"fixturesExpected\": ${expectedFiles.length},\n")
    sb.append(s"      \"fixturesCompared\": $fixturesCompared,\n")
    sb.append(s"""      "fixturesMissing": ${jsonStringArray(missing, "      ")},\n""")
    sb.append(s"      \"fixturesAgreeing\": $agreeing,\n")
    sb.append(s"      \"nodesCompared\": ${stats.counts("compared")},\n")
    sb.append(s"      \"nodesMapped\": ${stats.counts("mapped")},\n")
    sb.append(s"      \"nodesIgnored\": ${stats.counts("ignored")},\n")
    sb.append(s"      \"nodesElided\": ${stats.counts("elided")},\n")
    sb.append(s"      \"nodesFlattened\": ${stats.counts("flattened")},\n")
    sb.append(s"      \"nodesUnmapped\": ${stats.counts("unmapped")},\n")
    sb.append(s"""      "unmappedNames": ${jsonStringArray(stats.unmappedNames.toList.sorted, "      ")},\n""")
    sb.append(s"      \"divergenceCount\": ${divergences.length},\n")
    sb.append(s"      \"divergencesListed\": ${capped.length},\n")
    sb.append("      \"divergences\": [")
    if (capped.isEmpty) sb.append("]\n")
    else {
      sb.append("\n")
      capped.zipWithIndex.foreach { case ((fixture, d), i) =>
        val comma = if (i < capped.length - 1) "," else ""
        sb.append(
          s"""        {"fixture": "${esc(fixture)}", "path": "${esc(d.path)}", "expected": "${esc(d.expected)}", """
        )
        sb.append(s""""actual": "${esc(d.actual)}", "reason": "${esc(d.reason)}"}$comma\n""")
      }
      sb.append("      ]\n")
    }
    sb.append("    },\n")

    sb.append("    \"source_invariants\": {\n")
    sb.append(s"""      "verdict": "${esc(invariants.verdict)}",\n""")
    sb.append(
      """      "claim": "properties of the consumer's own output, checked against its input rather than against the reference",
        |      "authority": "independent",
        |      "caveat": "a failure here is a defect in the consumer regardless of what the reference does; a pass is not evidence of structural agreement.",
        |""".stripMargin
    )
    sb.append("      \"checks\": [\n")
    sb.append(renderLaneChecks(invariants.checks))
    sb.append("\n      ]\n")
    sb.append("    }\n")

    sb.append("  }\n")
    sb.append("}\n")
    sb.toString
  }

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
    var flatten: Set[String] = Set.empty
    var consumer = Paths.get(args.actual.stripSuffix("/")).getFileName.toString

    args.map.foreach { mapFile =>
      val m = Json.parseFile(Paths.get(mapFile))
      val mappings = m("mappings").asObject.view.mapValues(_.asString).toMap
      mapping = Some(mappings)
      ignored = m.get("ignored").map(_.asArray.map(_.asString).toSet).getOrElse(Set.empty)
      elide = m.get("elide").map(_.asArray.map(_.asString).toSet).getOrElse(Set.empty)
      flatten = m.get("flatten").map(_.asArray.map(_.asString).toSet).getOrElse(Set.empty)
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
            case Some(actTree) => compare(expTree, actTree, mapping, ignored, elide, flatten, source, found, stats)
          }
        }
        if (found.nonEmpty) divergences ++= found.map(name -> _)
        else agreeing += 1
      }
    }

    val fixturesCompared = expectedFiles.length - missing.length
    val divergenceList = divergences.toList

    // Agreement alone is gameable: a map that maps almost nothing compares almost nothing and so
    // agrees with almost everything. Depth -- the share of encountered nodes actually compared --
    // is what makes the agreement count mean anything, so it is reported rather than left for a
    // reader to derive.
    val encountered = stats.counts("compared") + stats.counts("unmapped")
    val depth = if (encountered == 0) 0.0 else stats.counts("compared").toDouble / encountered

    // The second lane runs over whatever the consumer actually produced, never over the
    // expectations, so it says something the first lane structurally cannot.
    lazy val invariants = SourceInvariants.run(
      expectedFiles
        .map(ef => Paths.get(args.actual, Paths.get(ef).getFileName.toString))
        .filter(Files.exists(_))
        .map(_.toString),
      mapped = mapping.isDefined,
      treeInventory = Json.parseFile(Paths.get("ast/treekind.json"))("kinds").asArray.map(_("name").asString).toSet,
      tokenInventory = Json.parseFile(Paths.get("ast/tokenkind.json"))("kinds").asArray.map(_("name").asString).toSet
    )

    args.report.foreach { reportPath =>
      val p = Paths.get(reportPath)
      Option(p.getParent).foreach(Files.createDirectories(_))
      Files.writeString(
        p,
        renderReport(
          consumer = consumer,
          expectedFiles = expectedFiles,
          fixturesCompared = fixturesCompared,
          missing = missing.toList.sorted,
          agreeing = agreeing,
          stats = stats,
          divergences = divergenceList,
          baseline = args.baseline,
          invariants = invariants
        ),
        StandardCharsets.UTF_8
      )
    }

    val unmappedSuffix = if (stats.counts("unmapped") > 0) s", ${stats.counts("unmapped")} unmapped" else ""
    println(
      s"$consumer: oracle_conformance $agreeing/$fixturesCompared fixtures agree, " +
        s"${divergenceList.length} divergences, ${stats.counts("compared")} nodes compared" +
        f" (depth ${depth * 100}%.0f%%)$unmappedSuffix"
    )
    println(
      s"$consumer: source_invariants ${invariants.verdict} — " +
        invariants.checks.map(c => s"${c.id} ${c.verdict}").mkString(", ")
    )
    if (missing.nonEmpty) System.err.println(s"  ${missing.length} fixture(s) had no consumer output")

    val oracleFailed = divergenceList.length > args.baseline
    if (oracleFailed) {
      System.err.println(s"FATAL: ${divergenceList.length} divergences exceeds baseline ${args.baseline}")
      divergenceList.take(10).foreach { case (fixture, d) =>
        System.err.println(s"  $fixture ${d.path}: expected '${d.expected}', got '${d.actual}' (${d.reason})")
      }
    }

    // The second lane gates too, and is not subject to `--baseline`. The ratchet exists because
    // agreement with the reference is approached incrementally, one mapping at a time; losing a
    // token's text is not a mapping gap that a consumer is partway through closing, it is the
    // output being wrong about its own input. A lane nobody can fail is decoration.
    val invariantsFailed = invariants.verdict == "fail"
    if (invariantsFailed) {
      System.err.println("FATAL: source invariants failed — the consumer's output disagrees with its own input")
      invariants.checks.filter(_.verdict == "fail").foreach { c =>
        System.err.println(s"  ${c.id}: ${c.detail}")
        c.failures.take(5).foreach(f => System.err.println(s"    $f"))
      }
    }

    if (oracleFailed || invariantsFailed) sys.exit(1)
  }
}
