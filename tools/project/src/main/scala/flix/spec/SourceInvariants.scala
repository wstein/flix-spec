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
      tokenInventory: Set[String],
      projectionSchema: Json = Json.parseFile(Spec.resolve("schemas/projection.schema.json")),
      /** Capabilities the consumer declared. A declared `tokens` turns "no token text" from a reason to stand down into
        * a failure: three of the four checks here are about token text, so an adapter that claims to emit it and then
        * emits none would otherwise slip from pass to `not-applicable` with nothing to say so.
        */
      capabilities: Set[String] = Set.empty
  ): Lane = {
    val docs = actualFiles.map(f => f -> Json.parseFile(Paths.get(f)))
    // --------------------------------------------------------------- shape
    val shapeErrors = new SchemaValidator.Errors
    val units = docs.flatMap { case (f, d) =>
      d.get("units") match {
        case Some(Json.JArray(items)) => items.map(f -> _)
        case _ =>
          shapeErrors.add(s"$f: missing or non-array 'units'")
          Nil
      }
    }
    val validUnits = scala.collection.mutable.ListBuffer.empty[(String, Json)]
    val kindsSeen = scala.collection.mutable.Set.empty[String]
    val tokensSeen = scala.collection.mutable.Set.empty[String]
    units.zipWithIndex.foreach { case ((f, unit), i) =>
      val validSource = unit.get("source").exists {
        case Json.JString(source) => source.nonEmpty
        case _                    => false
      }
      if (!validSource) shapeErrors.add(s"$f.units[$i]: missing, empty or non-string 'source'")
      unit.get("tree") match {
        case None => shapeErrors.add(s"$f.units[$i]: missing 'tree'")
        case Some(tree) =>
          val treeErrors = new SchemaValidator.Errors
          SchemaValidator.check(
            tree,
            projectionSchema("definitions")("Node"),
            projectionSchema,
            s"$f.units[$i].tree",
            treeErrors
          )
          treeErrors.toList.foreach(shapeErrors.add)
          if (treeErrors.isEmpty) {
            ProjectionSchemaValidator.walk(tree, s"$f.units[$i].tree", None, None, kindsSeen, tokensSeen, shapeErrors)
            if (validSource) validUnits += ((f, unit))
          }
      }
    }
    val shape = check(
      "document-shape",
      "every unit is a well-formed projected document: a source, a tree, and token leaves carrying token/text/start/end",
      units.length,
      shapeErrors.toList,
      None
    )
    val anyTokens = validUnits.exists(u => u._2.get("tree").exists(TokenAccounting.carriesTokens))

    val declaresTokens = capabilities.contains("tokens")
    val missingDeclaredTokens =
      "the projection map declares the `tokens` capability, but no tree carries token text. An adapter that " +
        "models tokens and then emits none is the regression that declaration exists to catch"

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
      if (anyTokens || declaresTokens) None
      else Some("the consumer's trees carry no token text, which docs/PROJECTION.md permits")
    }
    val badTokens =
      if (!anyTokens && declaresTokens && vocabularySkip.isEmpty) List(missingDeclaredTokens)
      else if (tokenSkip.isDefined) Nil
      else tokensSeen.toList.sorted.filterNot(tokenInventory).map(t => s"token '$t'")
    val tokenVocabulary = check(
      "token-vocabulary",
      "every token kind is one the reference's lexer defines, per ast/tokenkind.json",
      tokensSeen.size,
      badTokens,
      tokenSkip
    )

    // ----------------------------------------------------- token accounting
    val accountingSkip =
      if (anyTokens || declaresTokens) None
      else Some("the consumer's trees carry no token text, so there is nothing to account for")

    val accountingFailures =
      if (!anyTokens && declaresTokens) List(missingDeclaredTokens)
      else if (accountingSkip.isDefined) Nil
      else
        validUnits.toList.flatMap { case (f, unit) =>
          val sourceName = unit.get("source").map(_.asString).getOrElse("")
          val source = Spec.resolveSource(sourceName)
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
      if (accountingSkip.isDefined) 0 else validUnits.length,
      accountingFailures,
      accountingSkip
    )

    // ---------------------------------------------------- token positions
    // The strictly stronger form of token-accounting, and the one the schema already demands the data for:
    // `start` and `end` are required on every token leaf and, until now, were read by nothing in this repository.
    // A consumer could emit every token at line 1 col 1 and pass every gate.
    //
    // Three properties, and the third is the interesting one. Once tokens are known to sit at the offsets they
    // claim and to advance monotonically, whatever lies *between* them is exactly what the lexer did not tokenise
    // -- and that set can be named: whitespace, and the `$` the lexer steps over in `x.$and(y)`. Asserting it
    // positionally is what `token-accounting` cannot do: it compares concatenated text, where a `$` in a gap and a
    // `$` inside a string literal are indistinguishable, which is why a dropped `"$abc"` escapes it.
    val positionFailures =
      if (!anyTokens && declaresTokens) List(missingDeclaredTokens)
      else if (accountingSkip.isDefined) Nil
      else
        validUnits.toList.flatMap { case (f, unit) =>
          val sourceName = unit.get("source").map(_.asString).getOrElse("")
          val source = Spec.resolveSource(sourceName)
          if (sourceName.isEmpty || !Files.isRegularFile(source)) Nil
          else {
            val text = Files.readString(source, StandardCharsets.UTF_8)
            // Offset of the first character of each 1-indexed line, and where each line ends.
            val lines = text.linesWithSeparators.toVector
            val lineStarts = lines.scanLeft(0)(_ + _.length)
            def offset(pos: Json): Option[Int] = {
              val line = pos("line").asInt
              val col = pos("col").asInt
              if (line < 1 || line > lines.length) None
              else {
                // Bound the column by its own line, not by the file. Checking only the file bounds lets a
                // column run off the end of a short line and land on a later one, where the text can still
                // match: in "def\nf\n" a token claiming line 1 column 5 resolves to the `f` of line 2 and
                // every assertion below passes. That is exactly the misplacement this check exists to catch.
                val start = lineStarts(line - 1)
                val end = start + lines(line - 1).length
                val at = start + col - 1
                if (at < start || at > end) None else Some(at)
              }
            }
            val tokens = unit.get("tree").map(TokenAccounting.tokensInOrder).getOrElse(Nil)
            val problems = List.newBuilder[String]
            var previousEnd = 0
            tokens.foreach { t =>
              val body = t.get("text").map(_.asString).getOrElse("")
              (t.get("start").flatMap(offset), t.get("end").flatMap(offset)) match {
                case (Some(from), Some(to)) if from <= to =>
                  if (text.substring(from, to) != body)
                    problems += s"$sourceName: token at ${from}..${to} says '$body' but the source has " +
                      s"'${text.substring(from, to)}'"
                  if (from < previousEnd)
                    problems += s"$sourceName: token '$body' starts at $from, before the previous token ended " +
                      s"at $previousEnd"
                  else {
                    val gap = text.substring(previousEnd, from)
                    if (gap.exists(c => !c.isWhitespace && c != '$'))
                      problems += s"$sourceName: '${gap.replace("\n", "\\n")}' lies between two tokens but is " +
                        "neither whitespace nor the $ escape marker, so it is content no token accounts for"
                  }
                  previousEnd = math.max(previousEnd, to)
                case _ =>
                  problems += s"$sourceName: token '$body' carries a start/end that is not a position in its source"
              }
            }
            problems.result()
          }
        }

    val positions = check(
      "token-positions",
      "every token's text is exactly what its source says at its own start/end, tokens advance in order, " +
        "and what lies between them is only whitespace or the $ escape marker",
      if (accountingSkip.isDefined) 0 else validUnits.length,
      positionFailures,
      accountingSkip.map(_ => "the consumer's trees carry no token text, so there are no positions to check")
    )

    val checks = List(shape, kindVocabulary, tokenVocabulary, accounting, positions)
    val verdict =
      if (checks.exists(_.verdict == Fail)) Fail
      else if (checks.forall(_.verdict == NotApplicable)) NotApplicable
      else Pass

    Lane(verdict, checks)
  }
}
