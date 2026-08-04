package flix.spec

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** Exercises [[ProjectionMapValidator]] against maps written for the test.
  *
  * The previous version of this suite walked `ast/projection/` and asserted over whatever it found. Once the maps moved
  * to the consumers that own them, that directory could no longer exist, so every test iterated an empty list and
  * passed without checking anything -- three green tests covering nothing.
  *
  * One of them was also wrong on its own terms. It forbade a node appearing in both `mappings` and `ignored`, which the
  * validator deliberately permits: elision fires only at arity <= 1, so a precedence-chain node such as `ADDITIVE_EXPR`
  * is a transparent wrapper on every expression without a `+` and a real binary expression when one is present. An
  * earlier validator did reject the overlap, and a real consumer is what proved that rule wrong.
  *
  * Synthesising the maps here keeps the validator covered without this repository storing consumer data again.
  */
@RunWith(classOf[JUnitRunner])
class ProjectionMapTest extends AnyFunSuite with Matchers {

  private val repoRoot: Path = Paths.get("../..").toAbsolutePath.normalize()
  private val schema = Json.parseFile(repoRoot.resolve("schemas/projection-map.schema.json"))
  private val inventory =
    Json.parseFile(repoRoot.resolve("ast/treekind.json"))("kinds").asArray.map(_("name").asString).toSet

  /** Writes `body` to a temp file and validates it, returning the messages raised. */
  private def check(body: String): List[String] = {
    val dir = Files.createTempDirectory("projection-map-test")
    val file = dir.resolve("map.json")
    Files.writeString(file, body, StandardCharsets.UTF_8)
    try ProjectionMapValidator.validate(List(file.toString), schema, inventory).toList
    finally {
      Files.deleteIfExists(file)
      Files.deleteIfExists(dir)
    }
  }

  private val valid =
    """{
      |  "schemaVersion": 1,
      |  "consumer": "test-consumer",
      |  "mappings": { "function_definition": "Decl.Def", "call_expression": "Expr.Apply" },
      |  "ignored": ["source_file"],
      |  "elide": ["Type.Type"]
      |}""".stripMargin

  test("a well-formed map raises nothing") {
    check(valid) shouldBe empty
  }

  test("a mapping target outside the inventory is rejected") {
    // A stale or misspelled kind silently never matches, which reads as agreement -- strictly worse
    // than an unmapped node, which is reported as a gap.
    val errors = check(valid.replace("\"Decl.Def\"", "\"Decl.Defn\""))
    errors should have length 1
    errors.head should include("Decl.Defn")
    errors.head should include("not in ast/treekind.json")
  }

  test("a flattenCanonical entry outside the inventory is rejected") {
    // It names canonical kinds, like `elide` and unlike `flatten`, so the inventory applies.
    val errors = check(valid.replace("\"elide\": [\"Type.Type\"]", "\"flattenCanonical\": [\"Nope.Kind\"]"))
    errors should have length 1
    errors.head should include("flattenCanonical")
    errors.head should include("Nope.Kind")
  }

  test("a valid flattenCanonical entry is accepted") {
    check(
      valid.replace("\"elide\": [\"Type.Type\"]", "\"flattenCanonical\": [\"UsesOrImports.UseOrImportList\"]")
    ) shouldBe empty
  }

  test("an elide entry outside the inventory is rejected") {
    val errors = check(valid.replace("\"Type.Type\"", "\"Type.Nope\""))
    errors should have length 1
    errors.head should include("elide")
    errors.head should include("Type.Nope")
  }

  test("a node may be both mapped and ignored") {
    // Not a contradiction: the two entries describe different arities. Forbidding the overlap would
    // make precedence-chain grammars unexpressible, and a real consumer is what proved that.
    check(valid.replace("""["source_file"]""", """["source_file", "call_expression"]""")) shouldBe empty
  }

  test("a missing required key is rejected") {
    check(valid.replace("""  "consumer": "test-consumer",""", "")) should not be empty
  }

  test("an unexpected key is rejected") {
    val errors = check(
      valid.replace(
        """  "schemaVersion": 1,""",
        """  "schemaVersion": 1,
      |  "surprise": true,""".stripMargin
      )
    )
    errors.exists(_.contains("surprise")) shouldBe true
  }

  test("a note about an unknown node is rejected") {
    val errors = check(
      valid.replace(
        """  "elide": ["Type.Type"]""",
        """  "elide": ["Type.Type"],
      |  "notes": { "no_such_node": "why" }""".stripMargin
      )
    )
    errors.exists(_.contains("no_such_node")) shouldBe true
  }
}
