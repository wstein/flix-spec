package flix.spec

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

/** Normalisation is the one place in this repository where a committed artifact is *derived from another committed
  * artifact* rather than from the oracle. That makes two properties load-bearing, and neither is self-evident:
  *
  *   - the rewrite removes nodes and only nodes, so token text is invariant;
  *   - the renderer reproduces [[ProjectionExtractor]]'s bytes exactly, so the two writers cannot drift and turn every
  *     regeneration into a diff.
  */
@RunWith(classOf[JUnitRunner])
class NormalizerTest extends AnyFunSuite with Matchers {

  private val repoRoot: Path = Paths.get("../..").toAbsolutePath.normalize()

  private def contract: Transparency.Contract =
    Transparency.parse(Json.parseFile(repoRoot.resolve("ast/transparency.json")))

  private def trees(dir: String): List[Path] =
    Files
      .list(repoRoot.resolve(dir))
      .iterator()
      .asScala
      .filter(_.getFileName.toString.endsWith(".json"))
      .toList
      .sortBy(_.toString)

  private def tokens(node: Json): List[String] =
    node.get("kind") match {
      case None    => List(node("text").asString)
      case Some(_) => node.get("children").map(_.asArray).getOrElse(Nil).flatMap(tokens)
    }

  private def kinds(node: Json): List[String] =
    node.get("kind") match {
      case None    => Nil
      case Some(k) => k.asString :: node.get("children").map(_.asArray).getOrElse(Nil).flatMap(kinds)
    }

  // ------------------------------------------------------------------ rules

  private def tree(text: String): Json = Json.parse(text)
  private def node(kind: String, children: String*): String =
    s"""{"kind":"$kind","span":{"start":{"line":1,"col":1},"end":{"line":1,"col":2}},"children":[${children
        .mkString(",")}]}"""
  private def token(name: String, text: String): String =
    s"""{"token":"$name","text":"$text","start":{"line":1,"col":1},"end":{"line":1,"col":2}}"""

  private val rules = Transparency.Contract(
    "0" * 40,
    List(
      Transparency.Entry("Wrap", "elide", recoveryMarker = false, "x" * 40, Nil),
      Transparency.Entry("Boom", "splice", recoveryMarker = true, "x" * 40, Nil)
    )
  )

  private def normalized(text: String): String = Normalizer.render(Normalizer.normalize(tree(text), rules))

  test("an elided wrapper is dropped when empty and replaced by its child when singular") {
    normalized(node("Root", node("Wrap"))) shouldBe node("Root")
    normalized(node("Root", node("Wrap", node("Leaf")))) shouldBe node("Root", node("Leaf"))
  }

  test("an elided wrapper with two children is kept") {
    // The case that makes the rule safe. Splicing a branching node into its parent would discard genuine structure
    // and let a real disagreement pass as a normalisation decision.
    val branching = node("Wrap", node("A"), node("B"))
    normalized(node("Root", branching)) shouldBe node("Root", branching)
  }

  test("a chain of elided wrappers collapses to a fixed point") {
    // Type.Type re-closes over itself at every level of the type-precedence climb, so a rule that fired once would
    // leave the rest of the chain standing.
    normalized(node("Root", node("Wrap", node("Wrap", node("Wrap", node("Leaf")))))) shouldBe
      node("Root", node("Leaf"))
  }

  test("a spliced node is removed at any arity, and its children take its place in order") {
    normalized(node("Root", node("Boom", node("A"), node("B")), node("C"))) shouldBe
      node("Root", node("A"), node("B"), node("C"))
    normalized(node("Root", node("Boom"))) shouldBe node("Root")
  }

  test("a spliced node's token children survive, in position") {
    // TrailingDot wraps a real `.` from the source. The marker goes; the character it consumed does not.
    normalized(node("Root", node("Boom", token("Dot", ".")))) shouldBe node("Root", token("Dot", "."))
  }

  test("the root is never rewritten, even when its own kind carries a rule") {
    // It has no parent to be spliced into. Guarding it here is what lets every other rule be stated without an
    // "unless it is the root" clause.
    normalized(node("Wrap", node("Leaf"))) shouldBe node("Wrap", node("Leaf"))
    normalized(node("Boom", node("Leaf"))) shouldBe node("Boom", node("Leaf"))
  }

  test("token leaves are returned untouched") {
    normalized(token("Ident", "x")) shouldBe token("Ident", "x")
  }

  // --------------------------------------------------------------- artifacts

  test("rendering a committed tree reproduces its own bytes") {
    // The identity case, and the reason the two forms can both live under a diff gate: Normalizer.render and
    // ProjectionExtractor.printTree must agree byte for byte, or every regeneration would churn fixtures/expected.
    trees("fixtures/raw").foreach { f =>
      val text = Files.readString(f)
      Json.parseFile(f)("units").asArray.zipWithIndex.foreach { case (unit, i) =>
        val rendered = Normalizer.render(unit("tree"))
        withClue(s"${f.getFileName} unit $i: ")(text should include(rendered))
      }
    }
  }

  test("normalization preserves every token, in order, across the whole suite") {
    // Losslessness already asserts each form reconstructs its source; this asserts the two forms agree with *each
    // other*, which is the property that would break first if a rule ever discarded a subtree instead of unwrapping
    // one.
    val raw = trees("fixtures/raw")
    val normalizedTrees = trees("fixtures/expected")
    raw.map(_.getFileName.toString) shouldBe normalizedTrees.map(_.getFileName.toString)

    raw.zip(normalizedTrees).foreach { case (r, n) =>
      val rawUnits = Json.parseFile(r)("units").asArray
      val normUnits = Json.parseFile(n)("units").asArray
      rawUnits.zip(normUnits).foreach { case (ru, nu) =>
        withClue(s"${r.getFileName}: ")(tokens(nu("tree")) shouldBe tokens(ru("tree")))
      }
    }
  }

  test("the normalized trees contain no kind the contract removes, and the raw trees still do") {
    val removed = contract.all

    trees("fixtures/expected").foreach { f =>
      Json.parseFile(f)("units").asArray.foreach { unit =>
        withClue(s"${f.getFileName}: ")(kinds(unit("tree")).toSet.intersect(removed) shouldBe empty)
      }
    }

    val rawKinds = trees("fixtures/raw")
      .flatMap(f => Json.parseFile(f)("units").asArray.flatMap(u => kinds(u("tree"))))
      .toSet
    withClue("a rule whose kind the suite never exercises cannot be falsified by it: ") {
      removed.diff(rawKinds) shouldBe empty
    }
  }

  test("the two committed forms declare which they are") {
    trees("fixtures/raw").foreach(f => Json.parseFile(f)("form").asString shouldBe "raw")
    trees("fixtures/expected").foreach(f => Json.parseFile(f)("form").asString shouldBe "normalized")
  }
}
