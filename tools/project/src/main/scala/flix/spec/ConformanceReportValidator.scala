package flix.spec

import java.nio.file.Paths

/** Validates a conformance report against `schemas/conformance-report.schema.json`.
  *
  * Reports are not committed here -- they are produced by consumers, in consumer repositories, and are the artifact a
  * maintainer actually reads when deciding whether a grammar change helped. That makes the shape a published contract,
  * and a published contract nobody validates drifts. This is the same argument that put the projected trees under a
  * schema; the report deserves it for the same reason.
  *
  * Beyond the schema, it checks the two relationships the schema cannot express, both of which would let a report
  * overstate its own result:
  *
  *   - the counts must be internally consistent -- agreeing plus missing cannot exceed the number expected, and the
  *     listed divergences cannot outnumber the count they are a sample of;
  *   - the verdicts must follow from the numbers, so a report cannot claim `pass` while carrying divergences beyond its
  *     own baseline, or `pass` on a lane holding a failed check.
  *
  * Usage: `validateReport <report.json>`.
  */
object ConformanceReportValidator {

  def main(args: Array[String]): Unit = {
    val path = args.headOption.getOrElse {
      System.err.println("usage: validateReport <report.json>")
      sys.exit(2)
    }

    val doc = Json.parseFile(Paths.get(path))
    val schema = Json.parseFile(Paths.get("schemas/conformance-report.schema.json"))
    val errors = new SchemaValidator.Errors
    SchemaValidator.check(doc, schema, schema, path, errors)

    val oracle = doc("lanes")("oracle_conformance")
    val invariants = doc("lanes")("source_invariants")

    val expected = oracle("fixturesExpected").asInt
    val compared = oracle("fixturesCompared").asInt
    val missing = oracle("fixturesMissing").asArray.length
    val agreeing = oracle("fixturesAgreeing").asInt

    if (compared + missing != expected)
      errors.add(s"oracle_conformance: compared ($compared) + missing ($missing) != expected ($expected)")
    if (agreeing > compared)
      errors.add(s"oracle_conformance: agreeing ($agreeing) exceeds compared ($compared)")

    val divergenceCount = oracle("divergenceCount").asInt
    val listed = oracle("divergencesListed").asInt
    val actuallyListed = oracle("divergences").asArray.length
    if (listed != actuallyListed)
      errors.add(s"oracle_conformance: divergencesListed ($listed) != divergences length ($actuallyListed)")
    if (listed > divergenceCount)
      errors.add(s"oracle_conformance: more divergences listed ($listed) than counted ($divergenceCount)")

    val baseline = oracle("baseline").asInt
    val oracleVerdict = oracle("verdict").asString
    val expectedOracleVerdict = if (divergenceCount > baseline) "fail" else "pass"
    if (oracleVerdict != expectedOracleVerdict)
      errors.add(
        s"oracle_conformance: verdict '$oracleVerdict' does not follow from " +
          s"$divergenceCount divergences against baseline $baseline"
      )

    val checks = invariants("checks").asArray
    checks.foreach { c =>
      val id = c("id").asString
      val verdict = c("verdict").asString
      val failed = c("failed").asInt
      val listedFailures = c("failures").asArray.length
      if (verdict == "pass" && failed > 0) errors.add(s"source_invariants.$id: verdict 'pass' with $failed failures")
      if (verdict == "fail" && failed == 0) errors.add(s"source_invariants.$id: verdict 'fail' with no failures")
      if (verdict == "not-applicable" && (failed > 0 || c("checked").asInt > 0))
        errors.add(s"source_invariants.$id: verdict 'not-applicable' but something was checked or failed")
      if (listedFailures > failed)
        errors.add(s"source_invariants.$id: more failures listed ($listedFailures) than counted ($failed)")
    }

    val laneVerdict = invariants("verdict").asString
    val expectedLaneVerdict =
      if (checks.exists(_("verdict").asString == "fail")) "fail"
      else if (checks.nonEmpty && checks.forall(_("verdict").asString == "not-applicable")) "not-applicable"
      else "pass"
    if (laneVerdict != expectedLaneVerdict)
      errors.add(s"source_invariants: verdict '$laneVerdict' does not follow from its checks ($expectedLaneVerdict)")

    if (!errors.isEmpty) {
      System.err.println(s"FATAL: $path is not a valid conformance report")
      errors.toList.foreach(e => System.err.println(s"  $e"))
      sys.exit(1)
    }

    println(
      s"OK: $path is a valid conformance report — " +
        s"oracle_conformance ${oracle("verdict").asString}, source_invariants $laneVerdict"
    )
  }
}
