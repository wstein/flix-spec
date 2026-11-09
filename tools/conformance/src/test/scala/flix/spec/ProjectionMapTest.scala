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
  private val contract = Transparency.parse(Json.parseFile(repoRoot.resolve("ast/transparency.json")))

  /** Writes `body` to a temp file and validates it, returning the messages raised. */
  private def check(body: String): List[String] = {
    val dir = Files.createTempDirectory("projection-map-test")
    val file = dir.resolve("map.json")
    Files.writeString(file, body, StandardCharsets.UTF_8)
    try ProjectionMapValidator.validate(List(file.toString), schema, inventory, contract).toList
    finally {
      Files.deleteIfExists(file)
      Files.deleteIfExists(dir)
    }
  }

  private def runTool(main: String, args: String*): (Int, String) = {
    val classpath = List(
      ProjectionMapValidator.getClass.getProtectionDomain.getCodeSource.getLocation,
      classOf[scala.Option[_]].getProtectionDomain.getCodeSource.getLocation
    ).map(url => Paths.get(url.toURI).toString).mkString(java.io.File.pathSeparator)
    val command =
      List(Paths.get(System.getProperty("java.home"), "bin", "java").toString, "-cp", classpath, main) ++ args
    val process = new ProcessBuilder(command: _*).directory(repoRoot.toFile).redirectErrorStream(true).start()
    val output = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    (process.waitFor(), output)
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

  test("a recovery marker is accepted, and may also be mapped") {
    // Both, deliberately: the mapping is how the recovery lane knows which canonical kind to compare
    // it against, and the declaration is how the structural lane knows to splice it out first.
    check(
      valid.replace(
        """  "elide": ["Type.Type"]""",
        """  "elide": ["Type.Type"],
      |  "recoveryMarkers": ["call_expression"]""".stripMargin
      )
    ) shouldBe empty
  }

  test("a recovery marker that is also flattened or ignored is rejected") {
    // The failure this check exists for is silent: a node removed on both lanes has its recovery
    // shape measured nowhere, and the report still reads `pass`.
    val withMarker = valid.replace(
      """  "elide": ["Type.Type"]""",
      """  "elide": ["Type.Type"],
      |  "recoveryMarkers": ["error_node"]""".stripMargin
    )
    val cases = List(
      "ignored" -> withMarker.replace("""["source_file"]""", """["source_file", "error_node"]"""),
      "flatten" -> withMarker.replace(
        """  "ignored": ["source_file"],""",
        """  "ignored": ["source_file"],
      |  "flatten": ["error_node"],""".stripMargin
      )
    )
    cases.foreach { case (field, body) =>
      withClue(s"$field: ") {
        check(body).exists(e => e.contains("error_node") && e.contains("neither lane")) shouldBe true
      }
    }
  }

  test("a mapping onto an elided kind is rejected") {
    // Not merely dead: the canonical tree has no such node, so the consumer's own node stands where
    // nothing is expected and the mapping *manufactures* divergences. Measured on a real consumer,
    // seven such mappings were worth 31 of its 34 divergences.
    val errors = check(valid.replace("\"Expr.Apply\"", "\"Type.Type\""))
    errors should have length 1
    errors.head should include("elided by ast/transparency.json")
    errors.head should include("`ignored`")
  }

  test("a mapping onto a spliced kind needs the node declared as a recovery marker") {
    val onto = valid.replace("\"Expr.Apply\"", "\"ErrorTree\"")
    withClue("undeclared: ") {
      val errors = check(onto)
      errors should have length 1
      errors.head should include("unreachable in both lanes")
    }
    withClue("declared: ") {
      check(
        onto.replace(
          """  "elide": ["Type.Type"]""",
          """  "elide": ["Type.Type"],
      |  "recoveryMarkers": ["call_expression"]""".stripMargin
        )
      ) shouldBe empty
    }
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
  test("deprecation advice retains surviving scalar token wrappers and branching lists") {
    val occurrences = ProjectionMapValidator.canonicalOccurrences(repoRoot.resolve("fixtures/expected"))
    List("AnnotationList", "ModifierList").foreach { kind =>
      occurrences(kind) should contain(1)
      ProjectionMapValidator.removableEntries("elide", List(kind), contract, occurrences) shouldBe empty
    }
    val kind = "UsesOrImports.UseOrImportList"
    occurrences(kind).exists(_ >= 2) shouldBe true
    ProjectionMapValidator.removableEntries("flattenCanonical", List(kind), contract, occurrences) shouldBe empty
  }

  test("deprecation advice distinguishes elision arity from unconditional flattening") {
    val occurrences = Map("QName" -> List(2, 3), "ModifierList" -> List(0, 1, 2), "ErrorTree" -> List(1))
    val entries = List("QName", "ModifierList", "ErrorTree", "Type.Type", "Unknown.Kind", "Decl.Def")
    ProjectionMapValidator.removableEntries("elide", entries, contract, occurrences) shouldBe
      List("Type.Type")
    ProjectionMapValidator.removableEntries("flattenCanonical", entries, contract, occurrences) shouldBe
      List("Type.Type")
    ProjectionMapValidator.removableEntries("flattenCanonical", List("ErrorTree"), contract, Map.empty) shouldBe
      List("ErrorTree")
    ProjectionMapValidator.removableEntries("elide", List("QName"), contract, occurrences) shouldBe List("QName")
  }

  test("deprecation advice retains QName when Ident elision can change its arity") {
    val occurrences = ProjectionMapValidator.canonicalOccurrences(repoRoot.resolve("fixtures/expected"))
    occurrences("QName").forall(_ >= 2) shouldBe true
    occurrences("Ident") should contain(1)
    val file = Files.createTempFile("projection-map-real-composition", ".json")
    val report = Files.createTempFile("projection-map-real-report", ".json")
    try {
      val mappings = occurrences.keys.toList.sorted.map(k => s""""${Json.esc(k)}":"${Json.esc(k)}"""").mkString(",")
      def measure(elide: String): Int = {
        Files.writeString(
          file,
          s"""{"schemaVersion":1,"consumer":"composition","mappings":{$mappings},"ignored":["Ident","QName"],"elide":[$elide]}"""
        )
        val (exit, output) = runTool(
          "flix.spec.Conformance",
          "--actual",
          "fixtures/expected",
          "--map",
          file.toString,
          "--report",
          report.toString
        )
        withClue(output) { Files.size(report) should be > 0L }
        val divergences = Json.parseFile(report)("lanes")("oracle_conformance")("divergenceCount").asInt
        withClue(output) { exit shouldBe (if (divergences == 0) 0 else 1) }
        divergences
      }
      measure("\"Ident\",\"QName\"") shouldBe 0
      measure("\"Ident\"") should be > 0
    } finally {
      Files.deleteIfExists(file)
      Files.deleteIfExists(report)
    }
    ProjectionMapValidator.removableEntries("elide", List("Ident", "QName"), contract, occurrences) shouldBe empty
  }

  test("deprecation advice accounts for canonical rules under the other deprecated key") {
    val occurrences = Map("QName" -> List(2))
    ProjectionMapValidator.removableEntries(
      "elide",
      List("QName"),
      contract,
      occurrences,
      Set("QName")
    ) shouldBe List("QName")
    ProjectionMapValidator.removableEntries(
      "elide",
      List("QName"),
      contract,
      occurrences,
      Set("QName", "Ident")
    ) shouldBe empty
    ProjectionMapValidator.removableEntries(
      "elide",
      List("QName", "Type.Type"),
      contract,
      occurrences,
      Set("QName", "Ident", "Type.Type")
    ) shouldBe List("Type.Type")
    ProjectionMapValidator.removableEntries(
      "flattenCanonical",
      List("QName", "ErrorTree"),
      contract,
      occurrences,
      Set("QName", "Ident", "ErrorTree")
    ) shouldBe List("ErrorTree")
  }

  test("CLI advice retains a surviving elide entry when flattenCanonical can change its children") {
    val file = Files.createTempFile("projection-map-composition", ".json")
    try {
      Files.writeString(
        file,
        """{"schemaVersion":1,"consumer":"composition","mappings":{},"elide":["QName"],"flattenCanonical":["Ident"]}"""
      )
      val (exit, output) = runTool("flix.spec.ProjectionMapValidator", file.toString)
      exit shouldBe 0
      output should include("still yours:   QName")
      output should not include "removable now: QName"
    } finally Files.deleteIfExists(file)
  }

}
