package flix.spec

import java.nio.file.Files
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class DiagnosticConformanceTest extends AnyFunSuite {
  private def unit(ds: (String, Int)*): String = {
    val diagnostics = ds.map { case (kind, line) => s"""{"kind":"$kind","line":$line}""" }.mkString(",")
    s"""{"units":[{"source":"test.flix","diagnostics":[$diagnostics]}]}"""
  }

  private def compare(
      expected: List[String],
      actual: List[String],
      vocab: Conformance.Vocabulary = Conformance.Vocabulary()
  ): Conformance.DerivedLane = {
    val root = Files.createTempDirectory("diagnostics-test")
    val exp = Files.createDirectory(root.resolve("expected"))
    val act = Files.createDirectory(root.resolve("actual"))
    try {
      val paths = expected.zipWithIndex.map { case (doc, i) =>
        val path = exp.resolve(s"$i.json")
        Files.writeString(path, doc)
        path.toString
      }
      actual.zipWithIndex.foreach { case (doc, i) => Files.writeString(act.resolve(s"$i.json"), doc) }
      Conformance.runDiagnosticLane(paths, act.toString, vocab, baseline = 0)
    } finally {
      expected.indices.foreach(i => Files.deleteIfExists(exp.resolve(s"$i.json")))
      actual.indices.foreach(i => Files.deleteIfExists(act.resolve(s"$i.json")))
      Files.delete(exp); Files.delete(act); Files.delete(root)
    }
  }

  test("one unknown diagnostic cannot disarm a known kind's wrong line in another fixture") {
    val lane = compare(List(unit("Known" -> 1), unit("Other" -> 2)), List(unit("Known" -> 9), unit("Native" -> 2)))
    assert(lane.verdict == "fail")
    assert(lane.divergenceCount == 2)
    assert(lane.stats.counts("diagnosticsCompared") == 1)
    assert(lane.stats.unmapped("Native") == 1)
    assert(lane.caveat.contains("Partial diagnostic comparability"))
  }

  test("known and unknown diagnostics in the same unit remain partially comparable") {
    val lane = compare(List(unit("Known" -> 1, "Other" -> 2)), List(unit("Known" -> 8, "Native" -> 2)))
    assert(lane.divergenceCount == 2)
    assert(lane.caveat.contains("1 reference diagnostic(s)"))
  }

  test("explicit mappings gate missing kinds even alongside unknown diagnostics") {
    val lane = compare(
      List(unit("Known" -> 1)),
      List(unit("Native" -> 1)),
      Conformance.Vocabulary(diagnosticMappings = Map("ConsumerKnown" -> "Known"))
    )
    assert(lane.divergenceCount == 1)
  }

  test("a fully native vocabulary compares accept reject without inventing kind mismatches") {
    val lane = compare(List(unit("Known" -> 1), unit()), List(unit("Native" -> 7), unit()))
    assert(lane.verdict == "pass")
    assert(lane.stats.counts("diagnosticsCompared") == 0)
    assert(lane.caveat.contains("not full diagnostic agreement"))
  }

  test("fully comparable diagnostics still detect missing kinds and ignore advisory columns") {
    val lane = compare(List(unit("Known" -> 1, "Other" -> 2)), List(unit("Known" -> 1)))
    assert(lane.divergenceCount == 1)
    assert(lane.stats.counts("diagnosticsCompared") == 2)
  }

  test("diagnostic divergence reasons are permitted by the published report schema") {
    val schema = Json.parseFile(java.nio.file.Paths.get("../../schemas/conformance-report.schema.json"))
    List("diagnostic", "accept-reject").foreach { reason =>
      val errors = new SchemaValidator.Errors
      val d =
        Json.parse(s"""{"fixture":"f.json","path":"test.flix:1","expected":"a","actual":"b","reason":"$reason"}""")
      SchemaValidator.check(d, schema("definitions")("Divergence"), schema, "divergence", errors)
      assert(errors.isEmpty, errors.toList.mkString("\n"))
    }
  }
}
