package flix.spec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** The independent lane of a conformance report: what can be said about a consumer's output **without** the oracle.
  *
  * The two derived lanes compare the consumer against trees generated from the pinned reference -- `fixtures/expected`
  * for structure, `fixtures/raw` for error-recovery shape. Both measure *compatibility*, and both are honest about what
  * they cannot do: a derived suite cannot falsify its reference, so agreeing with a compiler bug scores as agreement.
  * `defects/ledger.json` exists because of that.
  *
  * These checks are different in kind. Each compares the consumer's output against its own *input*, or against its own
  * internal shape, and none consults an expected tree. A consumer can therefore fail this lane while passing the
  * derived ones, which is the interesting case: it means the output agrees with the reference and still lost something.
  * It can also pass this lane with no projection map at all, which is what makes the lane meaningful to a lexical
  * consumer that has no tree to compare.
  *
  * Every check reports one of three verdicts, and the third carries the weight:
  *
  *   - `pass` / `fail` -- the property was evaluated;
  *   - `not-applicable` -- it could not be, and *why*. `docs/PROJECTION.md` gates kind, child order and nesting while
  *     leaving spans and tokens uncompared, so a purely structural adapter that emits no token text is exercising a
  *     choice the contract grants it. Failing it for that would penalise a permitted decision; passing it would claim a
  *     property nothing established. Neither is true, so neither is reported.
  */
object SourceInvariants {

  final case class Check(
      id: String,
      verdict: String,
      claim: String,
      checked: Int,
      failed: Int,
      detail: String,
      failures: List[String]
  )

  final case class Lane(verdict: String, checks: List[Check])

  private val Pass = "pass"
  private val Fail = "fail"
  private val NotApplicable = "not-applicable"

  /** How many failures are listed per check before truncating. The count is always exact; the list is a sample. */
  private val MaxFailuresListed = 20

  private def check(id: String, claim: String, checked: Int, failures: List[String], skipped: Option[String]): Check =
    skipped match {
      case Some(reason) => Check(id, NotApplicable, claim, 0, 0, reason, Nil)
      case None if failures.nonEmpty =>
        Check(
          id,
          Fail,
          claim,
          checked,
          failures.length,
          s"${failures.length} of $checked failed",
          failures.take(MaxFailuresListed)
        )
      case None => Check(id, Pass, claim, checked, 0, s"$checked checked", Nil)
    }

  /** Runs every applicable invariant over the consumer's projected trees.
    *
    * @param actualFiles
    *   the consumer's output documents
    * @param mapped
    *   whether a projection map is in play, which decides whether the vocabulary checks can apply: with a map the
    *   consumer emits its own native names by design, and an unrecognised name is what the map translates rather than a
    *   defect this lane should report.
    */
  def run(
      actualFiles: List[String],
      mapped: Boolean,
      treeInventory: Set[String],
      tokenInventory: Set[String]
  ): Lane = {
    val docs = actualFiles.map(f => f -> Json.parseFile(Paths.get(f)))
    val units = docs.flatMap { case (f, d) => d.get("units").map(_.asArray).getOrElse(Nil).map(f -> _) }
    val anyTokens = units.exists(u => u._2.get("tree").exists(TokenAccounting.carriesTokens))

    // --------------------------------------------------------------- shape
    val shapeErrors = new SchemaValidator.Errors
    val kindsSeen = scala.collection.mutable.Set.empty[String]
    val tokensSeen = scala.collection.mutable.Set.empty[String]
    units.zipWithIndex.foreach { case ((f, unit), i) =>
      if (unit.get("source").isEmpty) shapeErrors.add(s"$f.units[$i]: missing 'source'")
      unit.get("tree") match {
        case None => shapeErrors.add(s"$f.units[$i]: missing 'tree'")
        case Some(tree) =>
          ProjectionSchemaValidator.walk(tree, s"$f.units[$i].tree", None, None, kindsSeen, tokensSeen, shapeErrors)
      }
    }
    val shape = check(
      "document-shape",
      "every unit is a well-formed projected document: a source, a tree, and token leaves carrying token/text/start/end",
      units.length,
      shapeErrors.toList,
      None
    )

    // -------------------------------------------------------- vocabularies
    val vocabularySkip =
      if (mapped) Some("a projection map is in play, so the consumer emits its own native vocabulary by design")
      else None

    val badKinds = if (mapped) Nil else kindsSeen.toList.sorted.filterNot(treeInventory).map(k => s"kind '$k'")
    val kindVocabulary = check(
      "kind-vocabulary",
      "every node kind is one the reference defines, per ast/treekind.json",
      kindsSeen.size,
      badKinds,
      vocabularySkip
    )

    val tokenSkip = vocabularySkip.orElse {
      if (anyTokens) None else Some("the consumer's trees carry no token text, which docs/PROJECTION.md permits")
    }
    val badTokens =
      if (tokenSkip.isDefined) Nil else tokensSeen.toList.sorted.filterNot(tokenInventory).map(t => s"token '$t'")
    val tokenVocabulary = check(
      "token-vocabulary",
      "every token kind is one the reference's lexer defines, per ast/tokenkind.json",
      tokensSeen.size,
      badTokens,
      tokenSkip
    )

    // ----------------------------------------------------- token accounting
    val accountingSkip =
      if (anyTokens) None
      else Some("the consumer's trees carry no token text, so there is nothing to account for")

    val accountingFailures =
      if (accountingSkip.isDefined) Nil
      else
        units.flatMap { case (f, unit) =>
          val sourceName = unit.get("source").map(_.asString).getOrElse("")
          val source = Paths.get(sourceName)
          if (sourceName.isEmpty) Some(s"$f: unit has no 'source', so its tree cannot be checked against one")
          else if (!Files.isRegularFile(source)) Some(s"$f: source '$sourceName' does not exist")
          else {
            val fromTree = unit.get("tree").map(TokenAccounting.reconstruct).getOrElse("")
            val fromDisk = TokenAccounting.squeeze(Files.readString(source, StandardCharsets.UTF_8))
            if (fromTree == fromDisk) None
            else
              Some(
                s"$sourceName: token text does not reconstruct its source\n" + TokenAccounting
                  .describeDivergence(fromTree, fromDisk)
              )
          }
        }

    val accounting = check(
      "token-accounting",
      "concatenating every token's text reproduces the source, ignoring whitespace and the $ escape marker",
      if (accountingSkip.isDefined) 0 else units.length,
      accountingFailures,
      accountingSkip
    )

    val checks = List(shape, kindVocabulary, tokenVocabulary, accounting)
    val verdict =
      if (checks.exists(_.verdict == Fail)) Fail
      else if (checks.forall(_.verdict == NotApplicable)) NotApplicable
      else Pass

    Lane(verdict, checks)
  }
}
