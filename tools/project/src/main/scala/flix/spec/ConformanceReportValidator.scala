package flix.spec

import java.nio.file.Paths

/** Validates a conformance report against `schemas/conformance-report.schema.json`.
  *
  * Reports are not committed here -- they are produced by consumers, in consumer repositories, and are the artifact a
  * maintainer actually reads when deciding whether a grammar change helped. That makes the shape a published contract,
  * and a published contract nobody validates drifts. This is the same argument that put the projected trees under a
  * schema; the report deserves it for the same reason.
  *
  * Beyond the schema, it checks the relationships the schema cannot express, all of which would let a report overstate
  * its own result:
  *
  *   - the counts must be internally consistent -- agreeing plus missing cannot exceed the number expected, the listed
  *     divergences cannot outnumber the count they are a sample of, and a lane cannot report divergences while also
  *     reporting that every fixture agreed;
  *   - the verdicts must follow from the numbers, so a report cannot claim `pass` while carrying divergences beyond its
  *     own baseline, or `pass` on a lane holding a failed check;
  *   - the recovery lane, which is scoped to the fixtures that recover from something, cannot claim more fixtures than
  *     the suite contains.
  *
  * Both derived lanes go through the identical checks, because they are rendered by one writer and a reader asked to
  * compare them must not first have to establish that they mean the same thing by the same field name.
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

    val invariants = doc("lanes")("source_invariants")

    // Both derived lanes get the identical treatment. They are rendered by one writer and validated by one checker,
    // because a reader who is asked to compare two lanes must not first have to establish that they mean the same
    // thing by the same field name.
    List("oracle_conformance", "recovery_conformance").foreach { name =>
      val lane = doc("lanes")(name)

      val expected = lane("fixturesExpected").asInt
      val compared = lane("fixturesCompared").asInt
      val missing = lane("fixturesMissing").asArray.length
      val agreeing = lane("fixturesAgreeing").asInt

      if (compared + missing != expected)
        errors.add(s"$name: compared ($compared) + missing ($missing) != expected ($expected)")
      if (agreeing > compared)
        errors.add(s"$name: agreeing ($agreeing) exceeds compared ($compared)")

      val divergenceCount = lane("divergenceCount").asInt
      val listed = lane("divergencesListed").asInt
      val actuallyListed = lane("divergences").asArray.length
      if (listed != actuallyListed)
        errors.add(s"$name: divergencesListed ($listed) != divergences length ($actuallyListed)")
      if (listed > divergenceCount)
        errors.add(s"$name: more divergences listed ($listed) than counted ($divergenceCount)")
      if (divergenceCount > 0 && agreeing == compared && compared > 0)
        errors.add(s"$name: $divergenceCount divergences, yet every compared fixture is reported as agreeing")

      // The list is the map author's work queue, so its order is load-bearing, not cosmetic.
      val unmapped = lane("unmapped").asArray.map(u => (u("name").asString, u("count").asInt))
      val ranked = unmapped.sortBy { case (n, count) => (-count, n) }
      if (unmapped != ranked)
        errors.add(s"$name: unmapped is not ranked by count then name")
      val unmappedTotal = unmapped.map(_._2).sum
      if (unmappedTotal != lane("nodesUnmapped").asInt)
        errors.add(
          s"$name: unmapped counts sum to $unmappedTotal but nodesUnmapped is ${lane("nodesUnmapped").asInt}"
        )

      val baseline = lane("baseline").asInt
      val verdict = lane("verdict").asString
      val stoodDown = lane.get("notApplicable")

      if (verdict == "not-applicable") {
        if (stoodDown.isEmpty)
          errors.add(s"$name: verdict 'not-applicable' with no reason; that is indistinguishable from 'not run'")
        if (expected > 0 || divergenceCount > 0 || lane("nodesCompared").asInt > 0)
          errors.add(s"$name: verdict 'not-applicable' but something was compared")
      } else {
        if (stoodDown.isDefined)
          errors.add(s"$name: carries a notApplicable reason but reports verdict '$verdict'")
        val expectedVerdict = if (divergenceCount > baseline) "fail" else "pass"
        if (verdict != expectedVerdict)
          errors.add(
            s"$name: verdict '$verdict' does not follow from $divergenceCount divergences against baseline $baseline"
          )
      }
    }

    // Only the recovery lane can stand down. oracle_conformance always has fixtures/expected to compare against, so a
    // `not-applicable` there would mean the comparison silently did nothing.
    if (doc("lanes")("oracle_conformance")("verdict").asString == "not-applicable")
      errors.add("oracle_conformance: this lane always has expectations to compare against and cannot stand down")

    // The recovery lane is scoped to the fixtures that recover from something, so it can never be answerable for more
    // fixtures than the whole suite. A report claiming otherwise has mixed up which lane it measured.
    val oracle = doc("lanes")("oracle_conformance")
    val recovery = doc("lanes")("recovery_conformance")
    if (recovery("fixturesExpected").asInt > oracle("fixturesExpected").asInt)
      errors.add(
        s"recovery_conformance is scoped to ${recovery("fixturesExpected").asInt} fixtures, more than the " +
          s"${oracle("fixturesExpected").asInt} the suite contains"
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
        s"oracle_conformance ${oracle("verdict").asString}, " +
        s"recovery_conformance ${recovery("verdict").asString}, " +
        s"source_invariants $laneVerdict"
    )
  }
}
