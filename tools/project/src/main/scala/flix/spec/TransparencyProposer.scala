package flix.spec

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._

/** Proposes candidates for `ast/transparency.json` by measuring the verbatim projected trees. Reports; never writes.
  *
  * The split between this and the committed contract is the same one `ast/unattachable.json` already draws. A machine
  * can find every kind that *behaves* like a wrapper across the suite; only a human can say that behaviour is a
  * property of the reference's grammar rather than an accident of which fixtures exist. So this prints a candidate
  * list, and a person writes the argument.
  *
  * The candidate rule is stated per occurrence, because that is how [[Normalizer]] applies the rules. It used to be
  * stated per kind -- "**every** occurrence has at most one child" -- and that was strictly stronger than anything the
  * normaliser needed. A kind that is a wrapper in most positions and a real branching node in a few was unproposable,
  * even though eliding it is safe by construction: the rewrite keeps every occurrence with two or more children. The
  * mismatch cost real coverage. `QName` is the clearest case -- a wrapper around every unqualified name, the dotted
  * sequence itself when the name is qualified -- and it stayed out of the contract for that reason alone.
  *
  * So two candidate rules, each the weakest that is safe for the kind it applies to:
  *
  *   - **`elide`** -- some occurrence has at most one child, and **no** occurrence has exactly one child that is a
  *     token. The second half is the whole safety condition: a node standing over a lone token is giving that token a
  *     role, and replacing it with the bare token strips that role. `Ident`, `Expr.Literal` and `Type.Variable` each
  *     hold exactly one token and nothing else; they pass any arity test and are emphatically not transparent.
  *   - **`elide-empty`** -- some occurrence is empty, but the kind does hold tokens directly, so `elide` is unsafe for
  *     it. `ModifierList` and `AnnotationList` are the cases: both consume their tokens by `advance()` into the list,
  *     so the list *is* the only thing saying those tokens are modifiers or annotations. Dropping the empty occurrences
  *     takes away nothing, because an empty node has no content to lose.
  *
  * Either way, removing the occurrence changes depth and nothing else: not sibling order, not token text, not which
  * node labels a token. That is a claim about the reference's structure, which is the only kind `ast/transparency.json`
  * accepts. The counts printed beside each candidate say how often the rule would actually fire, so a reader can tell a
  * wrapper that dominates the kind from one that is a rare special case.
  *
  * Usage: `proposeTransparency`. Run from the repository root.
  */
object TransparencyProposer {

  /** Per-kind occurrence census. `singleToken` is the safety-critical one: it counts the occurrences `elide` would
    * replace with a bare token, and a single one of them disqualifies the rule.
    */
  private final case class Shape(
      occurrences: Int,
      empty: Int,
      singleNode: Int,
      singleToken: Int,
      branching: Int,
      directTokens: Int
  ) {

    /** How often `elide` would fire: the empty and single-child occurrences. */
    def elidable: Int = empty + singleNode
  }

  private def walk(node: Json, shapes: scala.collection.mutable.Map[String, Shape]): Unit =
    node.get("kind").foreach { k =>
      val kind = k.asString
      val children = node.get("children").map(_.asArray).getOrElse(Nil)
      val tokens = children.count(_.get("kind").isEmpty)
      val prior = shapes.getOrElse(kind, Shape(0, 0, 0, 0, 0, 0))
      val loneToken = children.length == 1 && tokens == 1
      shapes(kind) = Shape(
        occurrences = prior.occurrences + 1,
        empty = prior.empty + (if (children.isEmpty) 1 else 0),
        singleNode = prior.singleNode + (if (children.length == 1 && tokens == 0) 1 else 0),
        singleToken = prior.singleToken + (if (loneToken) 1 else 0),
        branching = prior.branching + (if (children.length > 1) 1 else 0),
        directTokens = prior.directTokens + tokens
      )
      children.foreach(walk(_, shapes))
    }

  def main(args: Array[String]): Unit = {
    // The verbatim trees, never the normalised ones: a rule cannot be proposed from a tree the rules have already
    // been applied to, and a committed rule cannot be contradicted by one either.
    val dir = Paths.get(Spec.RawDir)
    if (!Files.isDirectory(dir)) {
      System.err.println(s"FATAL: no raw projected trees in $dir/ — run generateFixtures first")
      sys.exit(1)
    }
    val files = Files
      .list(dir)
      .iterator()
      .asScala
      .map(_.toString)
      .filter(_.endsWith(".json"))
      .toList
      .sorted

    if (files.isEmpty) {
      System.err.println(s"FATAL: no raw projected trees in $dir/")
      sys.exit(1)
    }

    val shapes = scala.collection.mutable.Map.empty[String, Shape]
    files.foreach(f => Json.parseFile(Paths.get(f))("units").asArray.foreach(u => walk(u("tree"), shapes)))

    val committed = Transparency.parse(Json.parseFile(Transparency.ContractFile))
    // A recovery marker is spliced deliberately and at every arity, which is stronger than either elision rule
    // by design -- its shape is measured in the recovery lane instead. Reporting it as a weaker candidate would
    // be advice to undo that split, so it is reported as settled.
    def status(name: String, want: String): String = contractRule(committed, name) match {
      case Some(`want`)                                      => s"in contract ($want)"
      case Some("splice") if committed.recoveryMarkers(name) => "in contract (splice — recovery marker)"
      case Some(other)                                       => s"in contract as `$other` — reconsider"
      case None                                              => "NOT IN CONTRACT — argue it or leave it out"
    }

    val elideCandidates = shapes.toList
      .filter { case (_, s) => s.elidable > 0 && s.singleToken == 0 }
      .sortBy { case (name, s) => (-s.elidable, name) }

    val emptyCandidates = shapes.toList
      .filter { case (_, s) => s.singleToken > 0 && s.empty > 0 }
      .sortBy { case (name, s) => (-s.empty, name) }

    println(s"Measured ${files.length} projected tree(s); ${shapes.size} distinct kinds.")
    println()
    println("`elide` candidates — never stands over a lone token, so substitution is safe:")
    println(f"${"kind"}%-32s ${"fires"}%7s ${"of"}%7s ${"branching"}%10s  status")
    elideCandidates.foreach { case (name, s) =>
      println(f"$name%-32s ${s.elidable}%7d ${s.occurrences}%7d ${s.branching}%10d  ${status(name, "elide")}")
    }

    println()
    println("`elide-empty` candidates — holds tokens directly, so only the empty occurrences can go:")
    println(f"${"kind"}%-32s ${"empty"}%7s ${"of"}%7s ${"lone-token"}%11s  status")
    emptyCandidates.foreach { case (name, s) =>
      println(f"$name%-32s ${s.empty}%7d ${s.occurrences}%7d ${s.singleToken}%11d  ${status(name, "elide-empty")}")
    }

    // The reverse direction is the one that goes stale silently: a fixture added later can give a kind a second child
    // or a token child, at which point the committed rule is no longer justified by the structure it claims.
    val contradicted = committed.entries
      .flatMap(e => shapes.get(e.name).map(s => (e, s)))
      .flatMap {
        case (e, s) if e.rule == "elide" && s.singleToken > 0 =>
          Some(
            s"  ${e.name}: elides, but stands over a lone token in ${s.singleToken} occurrence(s); " +
              "substitution would splice that token into the parent — the rule must be `elide-empty`"
          )
        case (e, s) if e.rule == "elide-empty" && s.directTokens == 0 =>
          Some(
            s"  ${e.name}: only drops when empty, but never holds a token directly; the stronger `elide` " +
              s"rule is safe and would also remove its ${s.singleNode} single-child occurrence(s)"
          )
        case (e, s) if e.rule == "elide" && s.elidable == 0 =>
          Some(
            s"  ${e.name}: elides, but every one of its ${s.occurrences} occurrence(s) branches, so the rule " +
              "removes nothing and nothing in the suite can falsify it"
          )
        case _ => None
      }

    if (contradicted.nonEmpty) {
      println()
      println("CONTRADICTED — committed, but measurement disagrees:")
      contradicted.foreach(println)
    }

    val unmeasured = committed.entries.map(_.name).filterNot(shapes.contains)
    if (unmeasured.nonEmpty) {
      println()
      println(s"UNMEASURED — committed but absent from the suite: ${unmeasured.mkString(", ")}")
    }

    println()
    println("This tool reports; it never writes. Add an entry to ast/transparency.json by hand, with a reason")
    println("that argues from the reference's own structure and citations a reader can check.")
  }

  private def contractRule(contract: Transparency.Contract, name: String): Option[String] =
    contract.entries.find(_.name == name).map(_.rule)
}
