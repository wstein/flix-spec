package flix.spec

import java.nio.file.{Path, Paths}

/** A consumer's record of which differences it has looked at and decided to keep for now.
  *
  * Separated from [[Conformance]]'s reporting so the decisions it encodes can be tested without running a comparison
  * and without a process exit. Everything here is a pure function of the file's contents and the identities a run
  * produced; the printing and the exit codes stay in the caller.
  */
object AcceptedSet {

  /** Every way the file can be unusable, stated rather than thrown. */
  sealed trait Problem { def message: String }

  final case class RevisionMismatch(file: String, recorded: String, current: String) extends Problem {
    def message: String =
      s"$file was recorded against fixture revision $recorded, but this suite is at $current.\n" +
        "  The fixtures moved, so every accepted difference has to be re-confirmed: the same path in the same " +
        "fixture can hold a different mismatch now, and these entries would hide it. Re-run without --accepted, " +
        "check what is reported, and record the set again."
  }

  final case class WrongConsumer(file: String, recorded: String, current: String) extends Problem {
    def message: String =
      s"$file records differences accepted by '$recorded', but this run is '$current'.\n" +
        "  An accepted set is one consumer's judgement about its own output. Applying it to another silently " +
        "grants differences nobody looked at."
  }

  final case class UnknownLanes(file: String, lanes: List[String]) extends Problem {
    def message: String =
      s"$file has entries under lane(s) that do not exist: ${lanes.mkString(", ")}.\n" +
        s"  Accepted lanes are ${KnownLanes.toList.sorted.mkString(", ")}. A misspelled lane is silently inert: " +
        "its entries never apply and its 'resolved' notices never fire."
  }

  /** The lanes an accepted set may name. `source_invariants` is deliberately absent: it reports checks rather than
    * divergences, and losing a token is not a difference a consumer is partway through closing.
    */
  val KnownLanes: Set[String] = Set("oracle_conformance", "recovery_conformance", "diagnostic_conformance")

  final case class Parsed(byLane: Map[String, Set[String]])

  /** What a run must do about one lane: what appeared that was not accepted, and what was accepted and is gone. */
  final case class Verdict(introduced: List[String], resolved: List[String]) {
    def failed: Boolean = introduced.nonEmpty
  }

  def parse(file: Path, currentRevision: String, currentConsumer: String): Either[Problem, Parsed] = {
    val name = file.toString
    val doc = Json.parseFile(file)

    val recorded = doc("fixtureRevision").asString
    if (recorded != currentRevision) return Left(RevisionMismatch(name, recorded, currentRevision))

    // A consumer binding, for the same reason as the revision binding: the identities are locations in the
    // canonical suite, and every consumer's output has those same locations. Nothing about the file's shape
    // says whose judgement it records, so without this one parser's accepted differences apply verbatim to
    // another's run.
    doc.get("consumer").map(_.asString).foreach { c =>
      if (c != currentConsumer) return Left(WrongConsumer(name, c, currentConsumer))
    }

    val byLane = doc("lanes").asObject.map { case (lane, v) => lane -> v.asArray.map(_.asString).toSet }
    val unknown = byLane.keySet.diff(KnownLanes).toList.sorted
    if (unknown.nonEmpty) return Left(UnknownLanes(name, unknown))

    Right(Parsed(byLane))
  }

  def verdict(parsed: Parsed, lane: String, identities: Set[String]): Verdict = {
    val accepted = parsed.byLane.getOrElse(lane, Set.empty)
    Verdict((identities -- accepted).toList.sorted, (accepted -- identities).toList.sorted)
  }
}
