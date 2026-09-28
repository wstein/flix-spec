package flix.spec

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.{ChangeSet, ReadAst, SyntaxTree, WeededAst}
import ca.uwaterloo.flix.language.ast.shared.{Origin, SecurityContext, Source, SourceName}
import ca.uwaterloo.flix.language.phase.{Lexer, Parser2, Weeder2}
import ca.uwaterloo.flix.util.Options

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

/** Runs one phase past this repository's pipeline, as a gate and never as a contract.
  *
  * `ProjectionExtractor` stops after `Parser2`, and everything published here is a statement about what the reference
  * *parses*. That boundary is deliberate and this tool does not move it: no projected tree changes, no schema gains a
  * field, and `Weeder2`'s output is never an expectation a consumer is measured against. Weeding is not a parse, and a
  * consumer that implements only a grammar must not be asked to reproduce it.
  *
  * What the boundary silently permitted is the reason this exists. A positive fixture only had to *parse*, so it could
  * encode syntax the reference rejects, and nothing here would notice:
  *
  *   - **Deprecated syntax.** Flix has no warning level -- `Severity` is `Error`, `Info`, `Hint`, and the latter two
  *     reach only the LSP -- so a construct in its grace period produces no diagnostic at all on the command line.
  *     `pub redef` is the one live case: `Weeder2` accepts it unless `--Xno-deprecated`, which makes it a hard error.
  *     It parses cleanly and yields `Decl.Redef -> ModifierList[KeywordPub]`, and when upstream finally removes it,
  *     `Parser2` will not change -- so no fixture, no digest and no lane here would move. The suite would go on
  *     blessing syntax the compiler rejects.
  *   - **The three `ParseError` variants raised only by `Weeder2`** -- `MissingRegion`, `NeedAtleastOne`,
  *     `MissingBinaryOperator` -- which `README.md` excludes from its diagnostic-kind coverage as out of scope.
  *
  * Run with `xnodeprecated = true`, which is what `Options.DefaultTest` uses and therefore the standard Flix's own
  * library is held to.
  *
  * Usage: `weedCheck [--strict]`. Without `--strict` it reports and exits zero.
  */
object WeedCheck {

  /** `crashed` is separate from `errors` on purpose: a diagnostic is the reference rejecting input, a thrown
    * `InternalCompilerException` is the reference failing to handle it. The second is a defect, not a verdict.
    */
  final case class Result(fixture: String, errors: List[String], crashed: Option[String] = None)

  /** Weeds one file. `Left` is a thrown exception -- the reference failing to handle input its own parser accepted --
    * and `Right` is the diagnostics it deliberately reported. [[DefectLedger]] reads this too.
    */
  def weed(file: Path): Either[String, List[String]] = {
    implicit val flix: Flix = new Flix()
    flix.setOptions(Options.Default.copy(xnodeprecated = true))
    flix.threadPool = new ca.uwaterloo.flix.util.ThreadPool(1)
    try {
      val text = new String(Files.readAllBytes(file), flix.defaultCharset)
      val src = Source.fromString(SourceName.PathName(file), Origin.User, SecurityContext.Plain, text)
      val readRoot = ReadAst.Root(Map(src -> ()))
      val (afterLexer, _) = Lexer.run(readRoot, Map.empty, ChangeSet.Everything)
      val (afterParser, _) = Parser2.run(afterLexer, SyntaxTree.empty, ChangeSet.Everything)
      val (_, weedErrors) = Weeder2.run(readRoot, None, afterParser, WeededAst.empty, ChangeSet.Everything)
      Right(weedErrors.map(e => e.getClass.getSimpleName).distinct.sorted)
    } catch {
      // Weeding a tree the parser accepted must not be able to kill this tool: an input the reference cannot
      // handle is exactly what it is here to surface.
      case t: Throwable => Left(s"${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")}")
    } finally flix.threadPool.shutdown()
  }

  def main(args: Array[String]): Unit = {
    val strict = args.contains("--strict")
    val dir = Paths.get("fixtures/positive")
    val files = Files
      .list(dir)
      .iterator()
      .asScala
      .map(_.toString)
      .filter(_.endsWith(".flix"))
      .toList
      .sorted

    val results = files.map { f =>
      val name = Paths.get(f).getFileName.toString
      weed(Paths.get(f)) match {
        case Right(errs) => Result(name, errs)
        case Left(crash) => Result(name, Nil, Some(crash))
      }
    }
    val dirty = results.filter(_.errors.nonEmpty)
    val crashed = results.filter(_.crashed.isDefined)

    println(s"Weeded ${results.length} positive fixture(s) with xnodeprecated=true.")
    if (dirty.isEmpty) println("None of them is rejected by Weeder2.")
    else {
      println(s"${dirty.length} fixture(s) parse but are rejected by Weeder2:")
      dirty.foreach(r => println(s"  ${r.fixture}: ${r.errors.mkString(", ")}"))
    }
    if (crashed.nonEmpty) {
      println(s"${crashed.length} fixture(s) CRASHED the reference -- these are defects, not verdicts:")
      crashed.foreach(r => println(s"  ${r.fixture}: ${r.crashed.get}"))
    }

    if (strict && dirty.nonEmpty) {
      System.err.println(
        "FATAL: a positive fixture parses but the reference rejects it. A positive fixture that is not a program " +
          "makes this suite an authority on syntax the compiler does not accept."
      )
      sys.exit(1)
    }
  }
}
