package flix.spec

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

/** The comparison and the normaliser compute the same fixed point over the same rules, in two places, for two different
  * reasons: one writes `fixtures/expected`, the other applies a consumer's own declarations at comparison time. They
  * agreed *approximately* for a long time, and approximately is exactly wrong here -- the canonical trees have to
  * survive their own comparison, or every number the report carries is measured against a shape nothing else produces.
  *
  * `verify.sh` asserts that end to end by feeding `fixtures/raw` back in as a consumer. These are the unit-level
  * statements of the same property, including the shape that broke it.
  */
@RunWith(classOf[JUnitRunner])
class ConformanceTest extends AnyFunSuite with Matchers {

  private val repoRoot: Path = Paths.get("../..").toAbsolutePath.normalize()

  private def contract: Transparency.Contract =
    Transparency.parse(Json.parseFile(repoRoot.resolve("ast/transparency.json")))

  private def node(kind: String, children: String*): String =
    s"""{"kind":"$kind","span":{"start":{"line":1,"col":1},"end":{"line":1,"col":2}},"children":[${children
        .mkString(",")}]}"""

  /** The kind-only shape both sides are reduced to before comparison, rendered for readable failures. */
  private def shape(tree: Json): String = tree.get("kind") match {
    case None => ""
    case Some(k) =>
      val kids = tree.get("children").map(_.asArray).getOrElse(Nil).map(shape).filter(_.nonEmpty)
      if (kids.isEmpty) k.asString else s"${k.asString}(${kids.mkString(",")})"
  }

  test("normalizing a wrapper around an empty error marker removes both") {
    // The shape that proved a single-pass rule application wrong, and it is not hypothetical: the reference produces
    // `Type.Type > ErrorTree` with an empty ErrorTree on a malformed trait signature. Splicing the marker leaves the
    // wrapper childless, and only a bottom-up fixed point sees that the wrapper is now elidable too.
    val tree = Json.parse(node("Decl.Signature", node("Ident"), node("Type.Type", node("ErrorTree"))))
    shape(Normalizer.normalize(tree, contract)) shouldBe "Decl.Signature(Ident)"
  }

  test("the canonical trees survive their own comparison") {
    // fixtures/expected must equal normalize(fixtures/raw) *and* the comparison must reach the same result when the
    // raw tree is handed to it as a consumer declaring the same rules. Two implementations of one fixed point.
    val raws =
      Json.parseFile(repoRoot.resolve("fixtures/raw/declarations__trait-and-instance-with-an-operator-signature.json"))
    val normalized =
      Json.parseFile(
        repoRoot.resolve("fixtures/expected/declarations__trait-and-instance-with-an-operator-signature.json")
      )

    val derived = shape(Normalizer.normalize(raws("units").asArray.head("tree"), contract))
    val committed = shape(normalized("units").asArray.head("tree"))
    derived shouldBe committed
  }

  test("removing the recovery markers from the contract keeps them in the tree") {
    // The recovery lane's whole expectation, in one line: the same wrappers gone, the markers still standing.
    val tree = Json.parse(node("Root", node("Type.Type", node("ErrorTree", node("Ident")))))
    shape(Normalizer.normalize(tree, contract)) shouldBe "Root(Ident)"
    shape(Normalizer.normalize(tree, contract.withoutRecoveryMarkers)) shouldBe "Root(ErrorTree(Ident))"
  }

  test("the recovery scope is a proper, non-empty subset of the suite") {
    // A lane scoped to everything measures the wrong question; one scoped to nothing reaches a verdict about no
    // evidence. Both would still report `pass`.
    val markers = contract.recoveryMarkers
    def hasMarker(p: Path): Boolean = {
      def walk(n: Json): Boolean = n.get("kind") match {
        case Some(k) => markers.contains(k.asString) || n.get("children").map(_.asArray).getOrElse(Nil).exists(walk)
        case None    => false
      }
      Json.parseFile(p)("units").asArray.exists(u => walk(u("tree")))
    }

    val all = Files
      .list(repoRoot.resolve("fixtures/raw"))
      .iterator()
      .asScala
      .filter(_.getFileName.toString.endsWith(".json"))
      .toList
    val scoped = all.filter(hasMarker)

    scoped should not be empty
    scoped.length should be < all.length
  }
}
