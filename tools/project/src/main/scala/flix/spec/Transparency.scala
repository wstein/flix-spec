package flix.spec

import java.nio.file.{Path, Paths}

/** Reads and checks `ast/transparency.json`, the curated contract that says which canonical `TreeKind`s are removed
  * when a projected tree is normalised.
  *
  * Four tools need the same answer and must not each re-derive it: [[Normalizer]] applies the rules when fixtures are
  * generated, [[NormalizationCheck]] re-applies them to prove `fixtures/expected` really is `normalize(fixtures/raw)`,
  * [[Conformance]] re-applies only the wrapper half to build the recovery lane's expectation, and [[KindStatus]] turns
  * the entries into vocabulary roles.
  *
  * The rules are deliberately few and deliberately asymmetric:
  *
  *   - `elide` — the node contributes exactly one edge and no leaf content. Dropped when empty, replaced by its child
  *     when it has one, and **kept** at two or more children, because splicing a branching node would discard real
  *     structure rather than a wrapper.
  *   - `splice` — the node's children are lifted into its parent at any arity. Stronger, and reserved for the error
  *     vocabulary, which is not syntax: keeping it in the canonical tree would make every conformance comparison a
  *     comparison of error-recovery strategies, which no two parsers share.
  *
  * What this loader will not do is judge an entry by its effect on a consumer. Every entry's `reason` must argue from
  * the reference's own structure, and [[check]] rejects one that names a consumer instead — with a single instrumented
  * consumer, nothing else in this repository can tell a neutral rule from a consumer-shaped one.
  */
object Transparency {

  val ContractFile: Path = Paths.get("ast/transparency.json")
  private val SchemaFile: Path = Paths.get("schemas/transparency.schema.json")

  /** One curated rule. `recoveryMarker` is a property of the kind, not of the rule: it says the node marks error
    * recovery rather than syntax, which is what moves its shape into the recovery lane.
    */
  final case class Entry(name: String, rule: String, recoveryMarker: Boolean, reason: String, citations: List[String])

  final case class Contract(upstreamCommit: String, entries: List[Entry]) {

    /** Kinds removed when empty and replaced by their child when singular. */
    val elide: Set[String] = entries.filter(_.rule == "elide").map(_.name).toSet

    /** Kinds whose children are lifted into the parent at any arity. */
    val splice: Set[String] = entries.filter(_.rule == "splice").map(_.name).toSet

    /** Kinds that mark error recovery rather than syntax. */
    val recoveryMarkers: Set[String] = entries.filter(_.recoveryMarker).map(_.name).toSet

    /** Every kind this contract removes. */
    val all: Set[String] = elide ++ splice

    /** The contract restricted to the rules that are *not* about error recovery.
      *
      * This is what the recovery lane compares against: `fixtures/raw` with its wrappers normalised away but its error
      * vocabulary intact. Comparing against raw verbatim would drown the recovery signal in wrapper divergences that
      * the main lane has already accounted for, and the point of the lane is to isolate recovery shape, not to
      * re-measure transparency.
      */
    def withoutRecoveryMarkers: Contract = Contract(upstreamCommit, entries.filterNot(_.recoveryMarker))
  }

  /** Phrases that make a `reason` evidence about a consumer rather than about the reference. */
  private val ConsumerWords = List("tree-sitter", "treesitter", "grammar-kit", "grammarkit", "antlr", "jetbrains")

  /** Parses the document, without checking it. [[check]] is the gate; this is the reader. */
  def parse(doc: Json): Contract =
    Contract(
      doc("upstreamCommit").asString,
      doc("treeKinds").asArray.map { e =>
        Entry(
          name = e("name").asString,
          rule = e("rule").asString,
          recoveryMarker = e.get("recoveryMarker").exists { case Json.JBool(b) => b; case _ => false },
          reason = e("reason").asString,
          citations = e.get("citations").map(_.asArray.map(_.asString)).getOrElse(Nil)
        )
      }
    )

  /** Every way the contract can be wrong that measurement or the inventory can detect. Returns the problems rather than
    * exiting, so callers can report them together with their own.
    */
  def problems(contract: Contract, inventory: Set[String], unattachable: Set[String]): List[String] = {
    val out = List.newBuilder[String]
    val names = contract.entries.map(_.name)

    names.diff(inventory.toList).foreach(n => out += s"entry '$n' is not in ast/treekind.json")
    names.diff(names.distinct).distinct.foreach(n => out += s"entry '$n' is listed twice")
    if (names != names.sorted) out += "entries are not sorted by name"

    // A kind that cannot appear in any tree cannot need a normalisation rule, and claiming one would be
    // unfalsifiable: no fixture and no corpus file could ever exercise it. The two curated files must not overlap.
    names.filter(unattachable).foreach { n =>
      out += s"entry '$n' is also claimed structurally-unattachable in ast/unattachable.json; a kind that " +
        "cannot appear in any tree needs no normalisation rule"
    }

    contract.entries.foreach { e =>
      if (e.recoveryMarker && e.rule != "splice")
        out += s"entry '${e.name}' is a recovery marker but its rule is '${e.rule}'; recovery markers are spliced"
      if (e.rule == "splice" && !e.recoveryMarker)
        out += s"entry '${e.name}' splices but is not marked a recovery marker; splicing is reserved for the " +
          "error vocabulary, because it discards a node that may carry real structure"
      val lower = e.reason.toLowerCase
      ConsumerWords.filter(lower.contains).foreach { w =>
        out += s"entry '${e.name}' justifies itself by naming a consumer ('$w'); a transparency rule must argue " +
          "from the reference's own structure"
      }
    }

    out.result()
  }

  /** Loads, schema-checks and consistency-checks the contract, exiting non-zero on any problem.
    *
    * `pinCommit` is asserted the way `ast/unattachable.json`'s is: the citations are line numbers into upstream source,
    * and a line number does not survive a pin bump on trust.
    */
  def load(root: Path = Paths.get("")): Contract = {
    val doc = Json.parseFile(root.resolve(ContractFile))
    val schema = Json.parseFile(root.resolve(SchemaFile))

    val errors = new SchemaValidator.Errors
    SchemaValidator.check(doc, schema, schema, "transparency.json", errors)
    if (!errors.isEmpty) {
      System.err.println("FATAL: ast/transparency.json does not conform to schemas/transparency.schema.json")
      errors.toList.foreach(e => System.err.println(s"  $e"))
      sys.exit(1)
    }

    val contract = parse(doc)
    val pinCommit = Json.parseFile(root.resolve(Paths.get("pin.json")))("upstream")("commit").asString
    if (contract.upstreamCommit != pinCommit) {
      System.err.println(
        s"FATAL: ast/transparency.json is at upstreamCommit ${contract.upstreamCommit}, " +
          s"but pin.json is at $pinCommit."
      )
      System.err.println("  Re-read every citation against the new source before restamping it.")
      sys.exit(1)
    }

    val inventory =
      Json.parseFile(root.resolve(Paths.get("ast/treekind.json")))("kinds").asArray.map(_("name").asString).toSet
    val unattachable =
      Json
        .parseFile(root.resolve(Paths.get("ast/unattachable.json")))("treeKinds")
        .asArray
        .map(_("name").asString)
        .toSet

    val found = problems(contract, inventory, unattachable)
    if (found.nonEmpty) {
      System.err.println("FATAL: ast/transparency.json is inconsistent")
      found.foreach(e => System.err.println(s"  $e"))
      sys.exit(1)
    }

    contract
  }
}
