package flix.spec

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

/** The committed transparency contract is the second hand-maintained file in this repository, and like the first
  * (`ast/unattachable.json`) it is the only place a wrong claim can enter a chain of otherwise-generated artifacts. So
  * measurement must be able to refute it, and the properties that make it *neutral* rather than merely convenient must
  * be asserted rather than reviewed once and trusted.
  */
@RunWith(classOf[JUnitRunner])
class TransparencyTest extends AnyFunSuite with Matchers {

  private val repoRoot: Path = Paths.get("../..").toAbsolutePath.normalize()

  private def contract: Transparency.Contract =
    Transparency.parse(Json.parseFile(repoRoot.resolve("ast/transparency.json")))

  private def rawTrees: List[Path] = {
    val dir = repoRoot.resolve("fixtures/expected")
    Files
      .list(dir)
      .iterator()
      .asScala
      .filter(_.getFileName.toString.endsWith(".json"))
      .toList
      .sortBy(_.toString)
  }

  test("the contract conforms to its schema") {
    val doc = Json.parseFile(repoRoot.resolve("ast/transparency.json"))
    val schema = Json.parseFile(repoRoot.resolve("schemas/transparency.schema.json"))
    val errors = new SchemaValidator.Errors
    SchemaValidator.check(doc, schema, schema, "transparency.json", errors)
    withClue(errors.toList.mkString("; "))(errors.isEmpty shouldBe true)
  }

  test("citations were read at the pinned commit") {
    // Line numbers do not survive a pin bump on trust. This is the same gate ast/unattachable.json carries, for the
    // same reason: a citation that silently drifts is worse than no citation.
    val pin = Json.parseFile(repoRoot.resolve("pin.json"))("upstream")("commit").asString
    contract.upstreamCommit shouldBe pin
  }

  test("entries are sorted, unique, in the inventory, and disjoint from the unattachable evidence") {
    val inventory =
      Json.parseFile(repoRoot.resolve("ast/treekind.json"))("kinds").asArray.map(_("name").asString).toSet
    val unattachable =
      Json.parseFile(repoRoot.resolve("ast/unattachable.json"))("treeKinds").asArray.map(_("name").asString).toSet

    Transparency.problems(contract, inventory, unattachable) shouldBe empty
  }

  test("no entry justifies itself by naming a consumer") {
    // The load-bearing rule of the whole design. With one instrumented consumer, no measurement here can distinguish
    // a neutral rule from a rule shaped by that consumer's grammar -- only the reasons can, so a reason that appeals
    // to a consumer removes the only evidence there is.
    val offenders = contract.entries.filter { e =>
      val r = e.reason.toLowerCase
      List("tree-sitter", "treesitter", "grammar-kit", "grammarkit", "antlr", "jetbrains", "consumer").exists(
        r.contains
      )
    }
    withClue(s"entries appealing to a consumer: ${offenders.map(_.name)} ")(offenders shouldBe empty)
  }

  test("every elided kind really is a one-edge, no-leaf wrapper in the fixtures") {
    // The falsification check. An `elide` entry claims the node contributes exactly one edge and no leaf content; an
    // occurrence with two children, or with a token child, is a direct counter-example and the entry is wrong.
    val maxChildren = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    val maxTokens = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)

    def walk(node: Json): Unit = node.get("kind").foreach { k =>
      val kind = k.asString
      val children = node.get("children").map(_.asArray).getOrElse(Nil)
      maxChildren(kind) = math.max(maxChildren(kind), children.length)
      maxTokens(kind) = math.max(maxTokens(kind), children.count(_.get("kind").isEmpty))
      children.foreach(walk)
    }
    rawTrees.foreach(f => Json.parseFile(f)("units").asArray.foreach(u => walk(u("tree"))))

    contract.entries.filter(_.rule == "elide").foreach { e =>
      withClue(s"${e.name} holds up to ${maxChildren(e.name)} child(ren): ")(maxChildren(e.name) should be <= 1)
      withClue(s"${e.name} holds a token child, so it gives that token a role: ")(maxTokens(e.name) shouldBe 0)
    }
  }

  test("splice is reserved for the error vocabulary, and covers all of it that can occur") {
    contract.splice shouldBe contract.recoveryMarkers

    // The error vocabulary the extractor's own fixture test knows about, minus UnclosedMark, which the parser always
    // overwrites and which ast/unattachable.json argues can appear in no tree at all.
    val errorKinds = Set("ErrorTree", "OperatorError", "TrailingDot")
    contract.recoveryMarkers shouldBe errorKinds

    val unattachable =
      Json.parseFile(repoRoot.resolve("ast/unattachable.json"))("treeKinds").asArray.map(_("name").asString).toSet
    withClue("UnclosedMark must be argued as unattachable, not as a transparency rule: ") {
      unattachable should contain("UnclosedMark")
      contract.all should not contain "UnclosedMark"
    }
  }

  test("the consistency check refuses every way an entry can be wrong") {
    // A gate that has only ever been shown to pass is not known to be a gate. Each of these is a shape review is
    // supposed to catch, expressed as an input the checker must reject.
    def entry(name: String, rule: String, marker: Boolean, reason: String) =
      Transparency.Entry(name, rule, marker, reason, Nil)

    val good = entry("Doc", "elide", marker = false, "a" * 40)
    val inventory = Set("Doc", "ErrorTree", "Nope")

    def problems(entries: Transparency.Entry*): List[String] =
      Transparency.problems(Transparency.Contract("0" * 40, entries.toList), inventory, Set("Nope"))

    problems(good) shouldBe empty

    withClue("out of order: ")(
      problems(entry("ErrorTree", "splice", marker = true, "b" * 40), good) should have length 1
    )
    withClue("repeated: ")(problems(good, good) should not be empty)
    withClue("absent from the inventory: ")(problems(good.copy(name = "Ghost")) should not be empty)
    withClue("also claimed unattachable: ")(problems(good.copy(name = "Nope")) should not be empty)
    withClue("splices without being a recovery marker: ")(problems(good.copy(rule = "splice")) should not be empty)
    withClue("a recovery marker that only elides: ")(
      problems(good.copy(recoveryMarker = true)) should not be empty
    )
    withClue("justified by a consumer: ")(
      problems(good.copy(reason = "tree-sitter-flix does not produce one, so it must be transparent")) should
        not be empty
    )
  }

  test("dropping the recovery markers leaves only wrapper rules") {
    val wrappersOnly = contract.withoutRecoveryMarkers
    wrappersOnly.splice shouldBe empty
    wrappersOnly.elide shouldBe contract.elide
  }
}
