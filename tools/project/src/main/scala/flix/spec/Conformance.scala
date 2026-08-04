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
  * The report it writes has **three lanes, never summed into one score**:
  *
  *   - `oracle_conformance` -- structure *modulo* recovery. Compared against `fixtures/expected/`, the normalised
  *     canonical tree, so wrapper nodes and the error vocabulary have already been removed by [[Transparency]]'s rules.
  *     Its expectations come from the pinned reference, so it inherits the reference's defects by construction and
  *     measures *compatibility*, not correctness: a consumer reproducing a compiler bug scores as agreeing, and one
  *     implementing the reference's intent instead scores as divergent.
  *   - `recovery_conformance` -- error-recovery shape, scoped to the fixtures whose raw tree actually contains recovery
  *     markers. Compared against `fixtures/raw/` with the wrapper rules applied but the error vocabulary left in, so
  *     the *only* difference from the first lane is the recovery markers. Comparing against raw verbatim was the
  *     obvious design and the wrong one: it would drown the recovery signal in wrapper divergences the first lane has
  *     already accounted for, and this lane exists to isolate recovery shape, not to re-measure transparency.
  *   - `source_invariants` -- [[SourceInvariants]], which consults no expected tree and can therefore contradict the
  *     reference. A consumer can pass the derived lanes and fail this one, and that case is the reason for the split:
  *     blanking one token's text leaves kind, child order and nesting untouched, so every fixture still agrees while
  *     the output has demonstrably lost its input.
  *
  * Splitting recovery out is not a way of forgiving it. It is the recognition that recovery shape is
  * implementation-specific by nature -- two parsers can agree completely about what a valid program means and share
  * nothing about how they resurface from a malformed one -- so a single score that mixed it with structure would mean
  * neither. The lane keeps its own verdict and its own baseline, and both gate.
  *
  * Usage: `conformance --actual <dir> [--map <file>] [--report <file>] [--baseline <n>] [--recovery-baseline <n>]`.
  * Exit status is non-zero when either derived lane exceeds its own baseline **or** the source-invariants lane fails. A
  * baseline is a ratchet for mapping coverage, which is closed incrementally; it does not apply to the third lane,
  * because losing a token is not a gap someone is partway through closing. Run from the repository root.
  */
object Conformance {

  private val ExpectedDir = ProjectionExtractor.NormalizedDir
  private val RawDir = ProjectionExtractor.RawDir
  private val MaxDivergencesPerFixture = 20

  final case class KTree(kind: String, children: List[KTree])

  private def kindTree(node: Json): Option[KTree] =
    node.get("kind") match {
      case None => None // token leaf
      case Some(k) =>
        val children = node.get("children").map(_.asArray).getOrElse(Nil).flatMap(kindTree)
        Some(KTree(k.asString, children))
    }

  /** Loads a projected document's units as kind-only trees, optionally normalising first.
    *
    * `normalizeWith` is what lets the recovery lane build its expectation from `fixtures/raw` without a second copy of
    * anything: it is the transparency contract minus its recovery markers, so the raw tree loses exactly the wrappers
    * the canonical tree lost and keeps exactly the error vocabulary the canonical tree dropped.
    */
  private def loadUnits(path: String, normalizeWith: Option[Transparency.Contract] = None): Map[String, KTree] =
    Json
      .parseFile(Paths.get(path))
      .get("units")
      .map(_.asArray)
      .getOrElse(Nil)
      .map { u =>
        val tree = normalizeWith.fold(u("tree"))(Normalizer.normalize(u("tree"), _))
        u("source").asString -> kindTree(tree).get
      }
      .toMap

  final class Stats {
    val counts = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    // Counted, not a set. docs/CONFORMANCE.md tells a map author to work down this list because
    // "the next most valuable mapping is always the top of it" -- which was only true if the list
    // were ordered by frequency, and a Set emitted alphabetically cannot be. The advice was sound
    // and the report could not support it.
    val unmapped = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    def inc(key: String): Unit = counts(key) += 1
  }

  final case class Divergence(path: String, expected: String, actual: String, reason: String)

  /** The consumer's own vocabulary declarations, read once from its projection map and applied identically by every
    * derived lane. Grouping them keeps the comparison's signature honest about what varies between lanes -- the
    * expectations and the baseline -- and what does not.
    */
  final case class Vocabulary(
      mapping: Option[Map[String, String]] = None,
      ignored: Set[String] = Set.empty,
      elide: Set[String] = Set.empty,
      flatten: Set[String] = Set.empty,
      flattenCanonical: Set[String] = Set.empty,
      recoveryMarkers: Set[String] = Set.empty
  ) {

    /** The vocabulary the structural lane uses: the consumer's own recovery markers are spliced out of its tree,
      * mirroring what normalisation already did to the canonical one.
      *
      * Transparency has to be symmetric or it is worse than absent -- the side that still has the wrapper faces a real
      * node on the other and reports a disagreement that is really a modelling difference. That argument was made for
      * wrappers and it applies unchanged to recovery markers: `fixtures/expected` has none, so a consumer that emits an
      * `ERROR` node would be penalised for information the canonical tree deliberately dropped.
      */
    def moduloRecovery: Vocabulary = copy(flatten = flatten ++ recoveryMarkers)
  }

  /** One measured lane whose expectations come from the reference. Both derived lanes have this shape, and the report
    * renders them through the same writer, because a reader comparing the two must not have to reconcile two layouts.
    */
  final case class DerivedLane(
      claim: String,
      caveat: String,
      baseline: Int,
      fixturesExpected: Int,
      fixturesMissing: List[String],
      fixturesAgreeing: Int,
      stats: Stats,
      divergences: List[(String, Divergence)],
      notApplicable: Option[String] = None
  ) {
    def fixturesCompared: Int = fixturesExpected - fixturesMissing.length
    def verdict: String =
      if (notApplicable.isDefined) "not-applicable"
      else if (divergences.length > baseline) "fail"
      else "pass"

    /** The share of encountered nodes actually compared. Agreement alone is gameable -- a map that maps almost nothing
      * compares almost nothing and so agrees with almost everything -- so depth is what makes the count mean anything.
      */
    def depth: Double = {
      val encountered = stats.counts("compared") + stats.counts("unmapped")
      if (encountered == 0) 0.0 else stats.counts("compared").toDouble / encountered
    }
  }

  /** Removes transparent nodes from a whole tree, bottom-up, as a single fixed point.
    *
    * Two rules, and they must be applied together rather than in sequence:
    *
    *   - `splice` -- the node's children replace it in its parent, at any arity. Stronger and more dangerous than
    *     elision, so it is opt-in per node name and belongs only on pure grouping constructs with no counterpart on the
    *     other side. Grammar-Kit needs it: `DECLARATION` wraps every declaration together with its doc, annotations and
    *     modifiers, while the reference makes those direct children of `Decl.Def`; without splicing, its whole subtree
    *     is never compared, which is why comparison depth was once 51%.
    *   - `elide` -- the node is dropped when it has no children and replaced by its child when it has exactly one. At
    *     two or more it is kept, since splicing a branching node would discard real structure rather than a wrapper.
    *
    * **Bottom-up, and that is not a detail.** An earlier revision applied the two rules once per level, in sequence,
    * and a node promoted into a level from below never met the other rule. The canonical trees contain exactly that
    * shape -- `Type.Type` wrapping an empty `ErrorTree` -- where splicing the marker leaves the wrapper childless and
    * therefore elidable, which a single pass could not see. This is the same fixed point [[Normalizer]] computes when
    * it writes `fixtures/expected`, and the two must agree exactly or the canonical trees would not survive their own
    * comparison. `verify.sh` asserts that by feeding `fixtures/raw` back in as a consumer.
    */
  private def transparent(
      node: KTree,
      splice: Set[String],
      elide: Set[String],
      stats: Stats,
      spliceCounter: String,
      elideCounter: String
  ): List[KTree] = {
    val kids = node.children.flatMap(transparent(_, splice, elide, stats, spliceCounter, elideCounter))
    if (splice.contains(node.kind)) {
      stats.inc(spliceCounter)
      kids
    } else if (elide.contains(node.kind) && kids.length <= 1) {
      stats.inc(elideCounter)
      kids
    } else List(KTree(node.kind, kids))
  }

  /** Applies transparency to a tree while leaving its root alone: the root has no parent to be spliced into. */
  private def transparentTree(
      root: KTree,
      splice: Set[String],
      elide: Set[String],
      stats: Stats,
      spliceCounter: String,
      elideCounter: String
  ): KTree =
    KTree(root.kind, root.children.flatMap(transparent(_, splice, elide, stats, spliceCounter, elideCounter)))

  /** Walks both trees in lockstep, appending divergences to `out`.
    *
    * Both trees arrive with transparency already applied, so this does one job: match kinds and arity, position by
    * position. Doing the two in one pass is what let the rules interact with the walk's own recursion and hid the
    * fixed-point bug described on [[transparent]].
    */
  private def compare(
      expected: KTree,
      actual: KTree,
      vocab: Vocabulary,
      path: String,
      out: scala.collection.mutable.Buffer[Divergence],
      stats: Stats
  ): Unit = {
    if (out.length >= MaxDivergencesPerFixture) return

    val actKind = vocab.mapping match {
      case None => actual.kind
      case Some(m) if m.contains(actual.kind) =>
        stats.inc("mapped")
        m(actual.kind)
      case Some(_) =>
        stats.inc("unmapped")
        stats.unmapped(actual.kind) += 1
        return // not a disagreement: we simply have no opinion yet
    }

    stats.inc("compared")
    if (expected.kind != actKind) {
      out += Divergence(path, expected.kind, actKind, "kind")
      return // subtree shape is meaningless once the kinds disagree
    }

    if (expected.children.length != actual.children.length)
      out += Divergence(
        path,
        s"${expected.children.length} children",
        s"${actual.children.length} children",
        "arity"
      )

    expected.children.zip(actual.children).zipWithIndex.foreach { case ((e, a), i) =>
      compare(e, a, vocab, s"$path.${expected.kind}[$i]", out, stats)
    }
  }

  /** Runs one derived lane: every expectation in `expectedFiles` against the consumer's output of the same name.
    *
    * The two lanes differ only in what they are handed. The mechanics -- which nodes are compared, how the map is
    * applied, what counts as a divergence -- are the same, and must be: a difference in method between them would make
    * the two divergence counts incomparable, and the whole point is that together they account for every divergence the
    * single lane used to report.
    */
  private def runLane(
      expectedFiles: List[String],
      normalizeWith: Option[Transparency.Contract],
      actualDir: String,
      vocab: Vocabulary,
      baseline: Int,
      claim: String,
      caveat: String
  ): DerivedLane = {
    val stats = new Stats
    val divergences = scala.collection.mutable.Buffer.empty[(String, Divergence)]
    val missing = scala.collection.mutable.Buffer.empty[String]
    var agreeing = 0

    expectedFiles.foreach { ef =>
      val name = Paths.get(ef).getFileName.toString
      val af = Paths.get(actualDir, name).toString
      if (!Files.exists(Paths.get(af))) missing += name
      else {
        val expUnits = loadUnits(ef, normalizeWith)
        val actUnits = loadUnits(af)
        val found = scala.collection.mutable.Buffer.empty[Divergence]
        expUnits.foreach { case (source, expRaw) =>
          actUnits.get(source).orElse(actUnits.values.headOption) match {
            case None => found += Divergence(source, "tree", "nothing", "missing-unit")
            case Some(actRaw) =>
              val expTree =
                transparentTree(expRaw, vocab.flattenCanonical, vocab.elide, stats, "flattenedCanonical", "elided")
              val actTree = transparentTree(actRaw, vocab.flatten, vocab.ignored, stats, "flattened", "ignored")
              compare(expTree, actTree, vocab, source, found, stats)
          }
        }
        if (found.nonEmpty) divergences ++= found.map(name -> _) else agreeing += 1
      }
    }

    DerivedLane(
      claim = claim,
      caveat = caveat,
      baseline = baseline,
      fixturesExpected = expectedFiles.length,
      fixturesMissing = missing.toList.sorted,
      fixturesAgreeing = agreeing,
      stats = stats,
      divergences = divergences.toList
    )
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
    * source changes, and nothing else in the repository summarises the resulting set in one value. It covers **both**
    * committed forms, because a change confined to nodes normalisation removes -- a different `ErrorTree` shape, say --
    * moves `fixtures/raw` and leaves `fixtures/expected` untouched. That change is invisible to the first lane and
    * decisive for the recovery lane, so a revision computed over the normalised trees alone would report two genuinely
    * different measurements as comparable.
    */
  private def provenance(expectedFiles: List[String], rawFiles: List[String]): List[(String, String)] = {
    val pin = Json.parseFile(Paths.get("pin.json"))
    val corpus = Json.parseFile(Paths.get("corpus/corpus.json"))
    val treeInv = Json.parseFile(Paths.get("ast/treekind.json"))
    val tokenInv = Json.parseFile(Paths.get("ast/tokenkind.json"))

    // Name and content of every expectation, so a renamed fixture moves the revision as surely as an edited one. The
    // parent directory is in the key so the two forms of one fixture cannot collide.
    val manifest = (rawFiles ++ expectedFiles)
      .map { f =>
        val p = Paths.get(f)
        s"${p.getParent.getFileName}/${p.getFileName}:${TreeKindExtractor.fileDigest(p)}"
      }
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

  /** Renders one derived lane. Both go through this, so the two cannot drift into different shapes. */
  private def renderDerivedLane(name: String, lane: DerivedLane, indent: String): String = {
    val sb = new StringBuilder
    val i = indent
    sb.append(s"""$i"$name": {\n""")
    sb.append(s"""$i  "verdict": "${lane.verdict}",\n""")
    sb.append(s"""$i  "claim": "${esc(lane.claim)}",\n""")
    sb.append(s"""$i  "authority": "derived",\n""")
    sb.append(s"""$i  "caveat": "${esc(lane.caveat)}",\n""")
    // Never blank when the lane stood down: "not applicable" without a reason is indistinguishable from "not run",
    // which is the same argument the source-invariants checks already make for their own `detail`.
    lane.notApplicable.foreach(reason => sb.append(s"""$i  "notApplicable": "${esc(reason)}",\n"""))
    sb.append(s"""$i  "baseline": ${lane.baseline},\n""")
    sb.append(s"""$i  "fixturesExpected": ${lane.fixturesExpected},\n""")
    sb.append(s"""$i  "fixturesCompared": ${lane.fixturesCompared},\n""")
    sb.append(s"""$i  "fixturesMissing": ${jsonStringArray(lane.fixturesMissing, s"$i  ")},\n""")
    sb.append(s"""$i  "fixturesAgreeing": ${lane.fixturesAgreeing},\n""")
    sb.append(s"""$i  "nodesCompared": ${lane.stats.counts("compared")},\n""")
    sb.append(s"""$i  "nodesMapped": ${lane.stats.counts("mapped")},\n""")
    sb.append(s"""$i  "nodesIgnored": ${lane.stats.counts("ignored")},\n""")
    sb.append(s"""$i  "nodesElided": ${lane.stats.counts("elided")},\n""")
    sb.append(s"""$i  "nodesFlattened": ${lane.stats.counts("flattened")},\n""")
    sb.append(s"""$i  "nodesFlattenedCanonical": ${lane.stats.counts("flattenedCanonical")},\n""")
    sb.append(s"""$i  "nodesUnmapped": ${lane.stats.counts("unmapped")},\n""")
    // Frequency first, name second so ties are stable across runs.
    val unmappedRanked = lane.stats.unmapped.toList.sortBy { case (name, n) => (-n, name) }
    sb.append(s"""$i  "unmapped": [""")
    if (unmappedRanked.isEmpty) sb.append("],\n")
    else {
      sb.append("\n")
      unmappedRanked.zipWithIndex.foreach { case ((n, count), idx) =>
        val comma = if (idx < unmappedRanked.length - 1) "," else ""
        sb.append(s"""$i    {"name": "${esc(n)}", "count": $count}$comma\n""")
      }
      sb.append(s"$i  ],\n")
    }
    val capped = lane.divergences.take(200)
    sb.append(s"""$i  "divergenceCount": ${lane.divergences.length},\n""")
    sb.append(s"""$i  "divergencesListed": ${capped.length},\n""")
    sb.append(s"""$i  "divergences": [""")
    if (capped.isEmpty) sb.append("]\n")
    else {
      sb.append("\n")
      capped.zipWithIndex.foreach { case ((fixture, d), idx) =>
        val comma = if (idx < capped.length - 1) "," else ""
        sb.append(s"""$i    {"fixture": "${esc(fixture)}", "path": "${esc(d.path)}", """)
        sb.append(s""""expected": "${esc(d.expected)}", "actual": "${esc(d.actual)}", """)
        sb.append(s""""reason": "${esc(d.reason)}"}$comma\n""")
      }
      sb.append(s"$i  ]\n")
    }
    sb.append(s"$i}")
    sb.toString
  }

  private def renderReport(
      consumer: String,
      expectedFiles: List[String],
      rawFiles: List[String],
      oracle: DerivedLane,
      recovery: DerivedLane,
      invariants: SourceInvariants.Lane
  ): String = {
    val sb = new StringBuilder
    sb.append("{\n")
    // 2: the flat body became two lanes and gained `provenance`. A consumer reading the old shape
    // finds none of its keys at the top level, which is the intended outcome -- silently keeping
    // them would let a reader take a compatibility number for a correctness one, the exact
    // conflation the split exists to end.
    // 5: oracle_conformance gained nodesFlattenedCanonical, when the map gained a canonical-side
    // counterpart to `flatten`.
    // 4: unmappedNames (an alphabetical string array) became unmapped (frequency-ranked objects
    // with counts), because the documented workflow -- work down the list, the top is the next
    // most valuable mapping -- was not something the old shape could support.
    // 3: source_invariants gained checksEvaluated/checksNotApplicable. Version 2 stood for exactly
    // as long as it took to measure one real consumer, which reported `pass` on the strength of a
    // single applicable check. Adding the fields without the bump would have been the silent
    // widening this file's own history argues against.
    // 6: recovery_conformance joined the lanes, and fixtureRevision now covers both committed
    // fixture forms. Both are breaking: a reader that required exactly two lanes now finds three,
    // and a revision computed the old way is not comparable to one computed the new way.
    sb.append("  \"schemaVersion\": 6,\n")
    sb.append("  \"generatedBy\": \"flix.spec.Conformance\",\n")
    sb.append(s"""  "consumer": "${esc(consumer)}",\n""")

    val prov = provenance(expectedFiles, rawFiles)
    sb.append("  \"provenance\": {\n")
    prov.zipWithIndex.foreach { case ((k, v), i) =>
      sb.append(s"""    "$k": "${esc(v)}"${if (i < prov.length - 1) "," else ""}\n""")
    }
    sb.append("  },\n")

    sb.append("  \"lanes\": {\n")

    sb.append(renderDerivedLane("oracle_conformance", oracle, "    "))
    sb.append(",\n")
    sb.append(renderDerivedLane("recovery_conformance", recovery, "    "))
    sb.append(",\n")

    sb.append("    \"source_invariants\": {\n")
    sb.append(s"""      "verdict": "${esc(invariants.verdict)}",\n""")
    // A lane verdict alone over-reads. Measuring tree-sitter-flix returned `pass` with three of
    // four checks standing down -- only document-shape was evaluated -- and "passes
    // source_invariants" is a very different sentence from "passed the one check that applied to
    // it". The counts make the difference impossible to omit when quoting the verdict.
    sb.append(s"""      "checksEvaluated": ${invariants.checks.count(_.verdict != "not-applicable")},\n""")
    sb.append(s"""      "checksNotApplicable": ${invariants.checks.count(_.verdict == "not-applicable")},\n""")
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

  private val Usage =
    "usage: Conformance --actual <dir> [--map <file>] [--report <file>] [--baseline <n>] [--recovery-baseline <n>]"

  final case class Args(
      actual: String,
      map: Option[String],
      report: Option[String],
      baseline: Int,
      recoveryBaseline: Int
  )

  private def parseArgs(argv: Array[String]): Args = {
    var actual: Option[String] = None
    var map: Option[String] = None
    var report: Option[String] = None
    var baseline = 0
    var recoveryBaseline = 0
    var i = 0
    while (i < argv.length) {
      argv(i) match {
        case "--actual"            => actual = Some(argv(i + 1)); i += 2
        case "--map"               => map = Some(argv(i + 1)); i += 2
        case "--report"            => report = Some(argv(i + 1)); i += 2
        case "--baseline"          => baseline = argv(i + 1).toInt; i += 2
        case "--recovery-baseline" => recoveryBaseline = argv(i + 1).toInt; i += 2
        case other =>
          System.err.println(s"unknown argument: $other")
          sys.exit(2)
      }
    }
    Args(
      actual.getOrElse {
        System.err.println(Usage)
        sys.exit(2)
      },
      map,
      report,
      baseline,
      recoveryBaseline
    )
  }

  /** Every projected document in a directory, sorted. */
  private def documents(dir: String): List[String] =
    Files.list(Paths.get(dir)).iterator().asScala.map(_.toString).filter(_.endsWith(".json")).toList.sorted

  /** Whether a raw projected document contains any of the recovery markers.
    *
    * This is what scopes the recovery lane. Running it over every fixture would report 130-odd fixtures agreeing about
    * error recovery when they contain no errors at all -- a large, meaningless majority that would make the lane's
    * verdict move for reasons unrelated to recovery.
    */
  private def hasRecoveryMarker(path: String, markers: Set[String]): Boolean = {
    def walk(node: Json): Boolean = node.get("kind") match {
      case Some(k) => markers.contains(k.asString) || node.get("children").map(_.asArray).getOrElse(Nil).exists(walk)
      case None    => false
    }
    Json.parseFile(Paths.get(path)).get("units").map(_.asArray).getOrElse(Nil).exists(u => walk(u("tree")))
  }

  def main(argv: Array[String]): Unit = {
    val args = parseArgs(argv)

    var vocab = Vocabulary()
    var consumer = Paths.get(args.actual.stripSuffix("/")).getFileName.toString

    args.map.foreach { mapFile =>
      val m = Json.parseFile(Paths.get(mapFile))
      val mappings = m("mappings").asObject.view.mapValues(_.asString).toMap
      def names(key: String): Set[String] = m.get(key).map(_.asArray.map(_.asString).toSet).getOrElse(Set.empty)
      vocab = Vocabulary(
        mapping = Some(mappings),
        ignored = names("ignored"),
        elide = names("elide"),
        flatten = names("flatten"),
        flattenCanonical = names("flattenCanonical"),
        recoveryMarkers = names("recoveryMarkers")
      )
      consumer = m("consumer").asString

      val inventory = Json.parseFile(Paths.get("ast/treekind.json"))("kinds").asArray.map(_("name").asString).toSet
      val bad = mappings.values.toSet.diff(inventory).toList.sorted
      if (bad.nonEmpty) {
        System.err.println(s"FATAL: projection map targets kinds absent from the inventory: $bad")
        sys.exit(1)
      }

      // A node removed on both lanes is a node whose recovery shape is never measured, which defeats the reason the
      // second lane exists. The distinction is the whole point of declaring recovery markers separately.
      val doubleDeclared = vocab.recoveryMarkers.intersect(vocab.flatten ++ vocab.ignored).toList.sorted
      if (doubleDeclared.nonEmpty) {
        System.err.println(
          s"FATAL: projection map declares these as recovery markers and also flattens or ignores them, " +
            s"so their recovery shape would never be measured: $doubleDeclared"
        )
        sys.exit(1)
      }
    }

    val expectedFiles = documents(ExpectedDir)
    if (expectedFiles.isEmpty) {
      System.err.println(s"FATAL: no expectations in $ExpectedDir/")
      sys.exit(1)
    }
    val rawFiles = documents(RawDir)
    if (rawFiles.isEmpty) {
      System.err.println(s"FATAL: no raw trees in $RawDir/")
      sys.exit(1)
    }

    val contract = Transparency.load()

    val oracleClaim =
      "agrees with the normalized canonical trees the pinned reference compiler produces, modulo error recovery"
    val oracleCaveat =
      "inherits the reference's defects by construction; agreeing with a compiler bug scores as agreement. See " +
        "defects/ledger.json. Error-recovery shape is deliberately not measured here — see recovery_conformance."
    val recoveryClaim =
      "reproduces the reference's error-recovery shape on the fixtures that recover from something"
    val recoveryCaveat =
      "error recovery is implementation-specific by nature: two parsers can agree completely about valid programs " +
        "and share nothing about how they resurface from a malformed one. A divergence here is weaker evidence than " +
        "one in oracle_conformance, which is why the two are never summed."

    val oracle = runLane(
      expectedFiles = expectedFiles,
      normalizeWith = None,
      actualDir = args.actual,
      vocab = vocab.moduloRecovery,
      baseline = args.baseline,
      claim = oracleClaim,
      caveat = oracleCaveat
    )

    // Scoped to the fixtures that actually recover from something, and compared against the raw trees with the
    // wrapper rules applied but the error vocabulary left standing -- on both sides. So the only thing this lane sees
    // that the first does not is recovery shape.
    //
    // A consumer that declares no recovery vocabulary is not measured here at all. It has nothing this lane could
    // compare, and failing it would penalise a modelling decision the contract never required -- the same argument
    // that gives the source-invariants checks a `not-applicable` verdict. Omission is the declaration, and the reason
    // is recorded so "not applicable" can never be mistaken for "not run".
    val recovery =
      if (vocab.recoveryMarkers.isEmpty)
        DerivedLane(
          claim = recoveryClaim,
          caveat = recoveryCaveat,
          baseline = args.recoveryBaseline,
          fixturesExpected = 0,
          fixturesMissing = Nil,
          fixturesAgreeing = 0,
          stats = new Stats,
          divergences = Nil,
          notApplicable = Some(
            "the projection map declares no recoveryMarkers, so this consumer models no error-recovery vocabulary " +
              "for the lane to compare"
          )
        )
      else
        runLane(
          expectedFiles = rawFiles.filter(hasRecoveryMarker(_, contract.recoveryMarkers)),
          normalizeWith = Some(contract.withoutRecoveryMarkers),
          actualDir = args.actual,
          vocab = vocab,
          baseline = args.recoveryBaseline,
          claim = recoveryClaim,
          caveat = recoveryCaveat
        )

    // The third lane runs over whatever the consumer actually produced, never over the
    // expectations, so it says something the derived lanes structurally cannot.
    lazy val invariants = SourceInvariants.run(
      expectedFiles
        .map(ef => Paths.get(args.actual, Paths.get(ef).getFileName.toString))
        .filter(Files.exists(_))
        .map(_.toString),
      mapped = vocab.mapping.isDefined,
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
          rawFiles = rawFiles,
          oracle = oracle,
          recovery = recovery,
          invariants = invariants
        ),
        StandardCharsets.UTF_8
      )
    }

    def summarise(name: String, lane: DerivedLane): String = lane.notApplicable match {
      case Some(reason) => s"$consumer: $name not-applicable — $reason"
      case None =>
        val unmapped = lane.stats.counts("unmapped")
        val suffix = if (unmapped > 0) s", $unmapped unmapped" else ""
        s"$consumer: $name ${lane.fixturesAgreeing}/${lane.fixturesCompared} fixtures agree, " +
          s"${lane.divergences.length} divergences, ${lane.stats.counts("compared")} nodes compared" +
          f" (depth ${lane.depth * 100}%.0f%%)$suffix"
    }
    println(summarise("oracle_conformance", oracle))
    println(summarise("recovery_conformance", recovery))
    println(
      s"$consumer: source_invariants ${invariants.verdict} — " +
        invariants.checks.map(c => s"${c.id} ${c.verdict}").mkString(", ")
    )

    def report(name: String, lane: DerivedLane): Boolean = {
      if (lane.fixturesMissing.nonEmpty)
        System.err.println(s"  $name: ${lane.fixturesMissing.length} fixture(s) had no consumer output")
      val failed = lane.verdict == "fail"
      if (failed) {
        System.err.println(s"FATAL: $name: ${lane.divergences.length} divergences exceeds baseline ${lane.baseline}")
        lane.divergences.take(10).foreach { case (fixture, d) =>
          System.err.println(s"  $fixture ${d.path}: expected '${d.expected}', got '${d.actual}' (${d.reason})")
        }
      }
      failed
    }

    // Both derived lanes gate, each against its own baseline. A lane that could only ever be read and never failed
    // would be decoration, and splitting recovery out was never meant to stop measuring it.
    val oracleFailed = report("oracle_conformance", oracle)
    val recoveryFailed = report("recovery_conformance", recovery)

    // The third lane gates too, and is not subject to any baseline. A ratchet exists because
    // agreement with the reference is approached incrementally, one mapping at a time; losing a
    // token's text is not a mapping gap that a consumer is partway through closing, it is the
    // output being wrong about its own input.
    val invariantsFailed = invariants.verdict == "fail"
    if (invariantsFailed) {
      System.err.println("FATAL: source invariants failed — the consumer's output disagrees with its own input")
      invariants.checks.filter(_.verdict == "fail").foreach { c =>
        System.err.println(s"  ${c.id}: ${c.detail}")
        c.failures.take(5).foreach(f => System.err.println(s"    $f"))
      }
    }

    if (oracleFailed || recoveryFailed || invariantsFailed) sys.exit(1)
  }
}
