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
  * The candidate rule is: **every occurrence has at most one child, and no occurrence has a token child**. Both halves
  * matter.
  *
  *   - "at most one", not "exactly one", because the empty case is the interesting one. `ast/coverage.json`'s
  *     `alwaysSingleChildWrapper` requires exactly one child and therefore misses every wrapper that is routinely empty
  *     — `Doc` on an undocumented declaration is the clearest example, and it is by node count the second largest
  *     wrapper in the suite.
  *   - "no token child", because a node holding a token is giving that token a role. `Ident`, `Expr.Literal` and
  *     `Type.Variable` each hold exactly one token and nothing else; they pass an arity test and are emphatically not
  *     transparent, since eliding them would splice a bare token into a parent that has no way to say what it was.
  *
  * A kind satisfying both contributes one edge and no leaf content, so removing it changes depth and nothing else: not
  * sibling order, not token text, not which node labels a token. That is a claim about the reference's structure, which
  * is the only kind of claim `ast/transparency.json` accepts.
  *
  * Usage: `proposeTransparency`. Run from the repository root.
  */
object TransparencyProposer {

  private final case class Shape(occurrences: Int, maxChildren: Int, withToken: Int, maxTokens: Int)

  private def walk(node: Json, shapes: scala.collection.mutable.Map[String, Shape]): Unit =
    node.get("kind").foreach { k =>
      val kind = k.asString
      val children = node.get("children").map(_.asArray).getOrElse(Nil)
      val tokens = children.count(_.get("kind").isEmpty)
      val prior = shapes.getOrElse(kind, Shape(0, 0, 0, 0))
      shapes(kind) = Shape(
        occurrences = prior.occurrences + 1,
        maxChildren = math.max(prior.maxChildren, children.length),
        withToken = prior.withToken + (if (tokens > 0) 1 else 0),
        maxTokens = math.max(prior.maxTokens, tokens)
      )
      children.foreach(walk(_, shapes))
    }

  def main(args: Array[String]): Unit = {
    val dir = Paths.get("fixtures/expected")
    if (!Files.isDirectory(dir)) {
      System.err.println(s"FATAL: no projected trees in $dir/ — run generateFixtures first")
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
      System.err.println(s"FATAL: no projected trees in $dir/")
      sys.exit(1)
    }

    val shapes = scala.collection.mutable.Map.empty[String, Shape]
    files.foreach(f => Json.parseFile(Paths.get(f))("units").asArray.foreach(u => walk(u("tree"), shapes)))

    val committed = Transparency.parse(Json.parseFile(Transparency.ContractFile))
    val candidates = shapes.toList
      .filter { case (_, s) => s.maxChildren <= 1 && s.maxTokens == 0 }
      .sortBy { case (name, s) => (-s.occurrences, name) }

    println(s"Measured ${files.length} projected tree(s); ${shapes.size} distinct kinds.")
    println()
    println("Candidates — every occurrence has at most one child and never a token child:")
    println(f"${"kind"}%-32s ${"occurrences"}%12s  status")
    candidates.foreach { case (name, s) =>
      val status =
        if (contractRule(committed, name).contains("elide")) "in contract (elide)"
        else if (contractRule(committed, name).isDefined) s"in contract (${contractRule(committed, name).get})"
        else "NOT IN CONTRACT — argue it or leave it out"
      println(f"$name%-32s ${s.occurrences}%12d  $status")
    }

    // The reverse direction is the one that goes stale silently: a fixture added later can give a kind a second child
    // or a token child, at which point the committed rule is no longer justified by the structure it claims.
    val contradicted = committed.entries
      .filter(_.rule == "elide")
      .flatMap(e => shapes.get(e.name).map(e.name -> _))
      .filter { case (_, s) => s.maxChildren > 1 || s.maxTokens > 0 }

    if (contradicted.nonEmpty) {
      println()
      println("CONTRADICTED — committed as `elide`, but measurement disagrees:")
      contradicted.foreach { case (name, s) =>
        println(s"  $name: up to ${s.maxChildren} child(ren), ${s.maxTokens} token child(ren) in one occurrence")
      }
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
