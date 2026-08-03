package flix.spec

import java.nio.file.{Files, Path, Paths}
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Validates `defects/ledger.json`: defects in the *reference compiler* that this suite inherits.
  *
  * The README states the accepted limitation up front -- a derived suite cannot falsify its reference, so if Flix has a
  * bug `flix-spec` inherits it and reports every agreeing parser as correct. That trade buys an oracle that cannot
  * drift, and it is the right trade. What it does not license is silence. A shared defect that nobody wrote down stops
  * being a defect and quietly becomes the specification, and the consumer who reproduces the reference's *intent*
  * rather than its *behaviour* is the one who pays, with a divergence report that blames their parser.
  *
  * So the ledger records them, and this validator keeps it honest in the two ways a prose list cannot be:
  *
  *   - **Every entry is falsifiable.** Each carries a minimized reproducer and a declarative assertion, re-checked
  *     against the pinned oracle on every run. When upstream fixes the defect the assertion stops holding and the build
  *     fails, saying so -- the entry is then closed deliberately rather than left to rot into folklore.
  *   - **Every entry expires.** Past its `review` date the build fails until a human re-triages it. A ledger without
  *     expiry accumulates entries nobody has looked at in a year, which is indistinguishable from having no ledger.
  *
  * The expiry gate is time-based, and that has a real cost worth stating rather than discovering: re-running CI on an
  * old commit or tag after one of its entries has expired will fail, even though nothing about that commit changed.
  * That is the intended direction of the ratchet -- staleness should be loud -- but it means a historical rebuild may
  * need the ledger's `review` dates advanced first. `docs/DEFECTS.md` says so where a maintainer will read it.
  */
object DefectLedger {

  private final case class Entry(
      id: String,
      title: String,
      reproducer: String,
      parsesCleanly: Boolean,
      absentKinds: List[String],
      presentKinds: List[String],
      upstreamStatus: String,
      upstreamIssue: Option[String],
      review: String
  )

  private def read(doc: Json): List[Entry] =
    doc("entries").asArray.map { e =>
      val a = e("assert")
      Entry(
        id = e("id").asString,
        title = e("title").asString,
        reproducer = e("reproducer").asString,
        parsesCleanly = a("parsesCleanly") == Json.JBool(true),
        absentKinds = a.get("absentKinds").map(_.asArray.map(_.asString)).getOrElse(Nil),
        presentKinds = a.get("presentKinds").map(_.asArray.map(_.asString)).getOrElse(Nil),
        upstreamStatus = e("upstreamStatus").asString,
        upstreamIssue = e.get("upstreamIssue").filterNot(_.isNull).map(_.asString),
        review = e("review").asString
      )
    }

  /** Every `kind` in a projected tree, with occurrence counts. */
  private def kindsOf(tree: Json): Map[String, Int] = {
    val counts = scala.collection.mutable.Map.empty[String, Int]
    def walk(n: Json): Unit = n.get("kind").foreach { k =>
      counts(k.asString) = counts.getOrElse(k.asString, 0) + 1
      n.get("children").foreach(_.asArray.foreach(walk))
    }
    walk(tree)
    counts.toMap
  }

  def main(args: Array[String]): Unit = {
    val repoRoot = Paths.get("").toAbsolutePath
    val ledgerPath = Paths.get("defects/ledger.json")
    val doc = Json.parseFile(ledgerPath)
    val schema = Json.parseFile(Paths.get("schemas/defect-ledger.schema.json"))

    val errors = new SchemaValidator.Errors
    SchemaValidator.check(doc, schema, schema, "ledger.json", errors)
    if (!errors.isEmpty) {
      System.err.println("FATAL: defects/ledger.json does not conform to schemas/defect-ledger.schema.json")
      errors.toList.foreach(e => System.err.println(s"  $e"))
      sys.exit(1)
    }

    val entries = read(doc)
    val fatal = scala.collection.mutable.ListBuffer.empty[String]

    val ids = entries.map(_.id)
    ids.diff(ids.distinct).distinct.foreach(id => fatal += s"$id: duplicate id")
    if (ids != ids.sorted) fatal += "entries are not sorted by id"

    // "Filed upstream" without a link is a claim nobody can check, and a link on an unfiled entry is
    // a link to nothing. Neither is a schema constraint, because the schema cannot relate two fields.
    entries.foreach { e =>
      (e.upstreamStatus, e.upstreamIssue) match {
        case ("filed", None)        => fatal += s"${e.id}: upstreamStatus is 'filed' but upstreamIssue is null"
        case ("not-filed", Some(_)) => fatal += s"${e.id}: upstreamStatus is 'not-filed' but upstreamIssue is set"
        case ("filed", Some(url)) if !url.startsWith("https://") =>
          fatal += s"${e.id}: upstreamIssue must be an https URL, got '$url'"
        case _ => // consistent
      }
    }

    val today = LocalDate.now()
    entries.foreach { e =>
      try {
        val due = LocalDate.parse(e.review)
        if (due.isBefore(today))
          fatal += s"${e.id}: review date ${e.review} has passed -- re-triage it and set a new date, or close it"
      } catch {
        case _: DateTimeParseException => fatal += s"${e.id}: review '${e.review}' is not a valid date"
      }
    }

    entries.foreach { e =>
      val repro = Paths.get(e.reproducer)
      if (!Files.isRegularFile(repro)) fatal += s"${e.id}: reproducer ${e.reproducer} does not exist"
      else fatal ++= verify(e, repro, repoRoot)
    }

    if (fatal.nonEmpty) {
      System.err.println("FATAL: defect ledger validation failed")
      fatal.foreach(e => System.err.println(s"  $e"))
      sys.exit(1)
    }

    val filed = entries.count(_.upstreamStatus == "filed")
    println(
      s"OK: ${entries.length} defect(s) still reproduce at this pin " +
        s"($filed filed upstream, ${entries.length - filed} not filed); none expired"
    )
  }

  /** Re-checks one entry's assertion against the pinned oracle. */
  private def verify(e: Entry, repro: Path, repoRoot: Path): List[String] = {
    val projection = ProjectionExtractor.project(repro, repoRoot)
    val kinds = kindsOf(Json.parse(projection.tree))
    val found = scala.collection.mutable.ListBuffer.empty[String]

    val clean = projection.diagnostics.isEmpty
    if (clean != e.parsesCleanly)
      found += s"${e.id}: expected parsesCleanly=${e.parsesCleanly}, got $clean " +
        s"(${projection.diagnostics.map(_.kind).mkString(", ")})"

    // The load-bearing check. A kind that was absent and is now present means upstream fixed the
    // defect: the entry is stale, and saying "fixed" out loud is the whole point of asserting it.
    e.absentKinds.filter(kinds.contains).foreach { k =>
      found += s"${e.id}: '$k' was expected to be unreachable but the reproducer now emits it -- " +
        "the defect appears fixed upstream; close this entry"
    }
    e.presentKinds.filterNot(kinds.contains).foreach { k =>
      found += s"${e.id}: '$k' was expected in the reproducer's tree but is absent -- " +
        "the reproducer no longer demonstrates the defect; minimize it again"
    }
    found.toList
  }
}
