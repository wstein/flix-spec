package flix.spec

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.{ChangeSet, SourcePosition, SyntaxTree, Token, TokenKind}
import ca.uwaterloo.flix.language.ast.shared.{AvailableClasses, Input, SecurityContext}
import ca.uwaterloo.flix.language.phase.{Lexer, Parser2, Reader}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** Emits a canonical projected concrete syntax tree for a single `.flix` file, conforming to
  * `schemas/projection.schema.json` (implementation plan section 3.1).
  *
  * Drives `Reader.run -> Lexer.run -> Parser2.run` directly rather than `Flix.check()`. The public route returns the
  * CST for the entire compilation, standard library included -- roughly a million nodes for a one-line fixture -- so
  * every expectation would embed the stdlib. The phase pipeline parses exactly the inputs it is given.
  */
object ProjectionExtractor {

  /** Bumped whenever the emitted format changes. */
  val ToolVersion = "1.0.0"

  private val OracleJar = Paths.get(".oracle/flix.jar")
  private val PinFile = Paths.get("pin.json")

  // ------------------------------------------------------------------ output

  def esc(s: String): String = {
    val sb = new StringBuilder
    for (c <- s) c match {
      case '"'           => sb.append("\\\"")
      case '\\'          => sb.append("\\\\")
      case '\n'          => sb.append("\\n")
      case '\r'          => sb.append("\\r")
      case '\t'          => sb.append("\\t")
      case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
      case c             => sb.append(c)
    }
    sb.toString
  }

  /** Sub-trait-qualified kind name, shared with [[TreeKindExtractor]] via [[TreeKindNaming]].
    *
    * Bare `toString` is not usable here: 13 simple names are reused across sub-traits, so `Expr.Apply` and `Type.Apply`
    * would both emit as `"Apply"` and the projection could not identify a node kind.
    */
  def kindName(kind: SyntaxTree.TreeKind): String = TreeKindNaming.qualifiedNameOf(kind)

  def tokenKindName(kind: TokenKind): String = kind match {
    case TokenKind.Err(_) => "Err"
    case other            => other.toString
  }

  def printPos(p: SourcePosition): String =
    s"""{"line":${p.lineOneIndexed},"col":${p.colOneIndexed}}"""

  def printToken(t: Token): String =
    s"""{"token":"${esc(tokenKindName(t.kind))}","text":"${esc(t.text)}","start":${printPos(t.start)},"end":${printPos(
        t.end
      )}}"""

  def printTree(tree: SyntaxTree.Tree): String = {
    val children = tree.children.iterator
      .map {
        case tok: Token           => printToken(tok)
        case sub: SyntaxTree.Tree => printTree(sub)
        case other                => s"""{"unknown":"${esc(String.valueOf(other))}"}"""
      }
      .mkString(",")
    s"""{"kind":"${esc(kindName(tree.kind))}","span":{"start":${printPos(tree.loc.start)},"end":${printPos(
        tree.loc.end
      )}},"children":[$children]}"""
  }

  // ------------------------------------------------------------------- parse

  /** A parsed fixture: its repo-relative source name, projected tree, and parser diagnostics. */
  case class Projection(source: String, tree: String, diagnostics: List[Diagnostic])

  /** A parser diagnostic. `kind` and `line` are gated by conformance; `col` and `message` are advisory (implementation
    * plan section 3.4) because both are recovery-dependent and shift between releases.
    */
  case class Diagnostic(kind: String, line: Int, col: Int, message: String)

  private val LocPattern = """([^\s,()]+\.flix):(\d+):(\d+)""".r

  /** Renders a diagnostic with absolute paths rewritten to repository-relative ones.
    *
    * Flix embeds the absolute source path in its rendered diagnostics. Committing that verbatim would make every
    * expectation machine-specific and fail the diff gate on any other checkout.
    */
  private def toDiagnostic(err: Any, repoRoot: Path): Diagnostic = {
    val raw = String.valueOf(err)
    val rendered = raw.replace(repoRoot.toString + java.io.File.separator, "").replace('\\', '/')
    val kind = rendered.takeWhile(c => c != '(').trim
    LocPattern.findFirstMatchIn(rendered) match {
      case Some(m) => Diagnostic(kind, m.group(2).toInt, m.group(3).toInt, rendered)
      case None    => Diagnostic(kind, 0, 0, rendered)
    }
  }

  /** Parses one file against the pinned oracle and projects the result. */
  def project(file: Path, repoRoot: Path): Projection = {
    val absolute = file.toAbsolutePath.normalize()
    val inputs = List(Input.RealFile(absolute, SecurityContext.Plain))

    implicit val flix: Flix = new Flix()
    // Flix.check() would call the private initForkJoinPool(); driving phases directly means
    // doing it here, or Lexer.run's parallel map throws NullPointerException.
    flix.threadPool = new java.util.concurrent.ForkJoinPool(1)

    val (afterReader, readerErrors) = Reader.run(inputs, AvailableClasses.empty)
    require(readerErrors.isEmpty, s"FATAL: reader errors for $file: $readerErrors")

    val (afterLexer, lexerErrors) = Lexer.run(afterReader, Map.empty, ChangeSet.Everything)
    val (afterParser, parserErrors) = Parser2.run(afterLexer, SyntaxTree.empty, ChangeSet.Everything)

    val units = afterParser.units.toList
    require(units.length == 1, s"FATAL: expected exactly one compilation unit, got ${units.length}")
    val (_, tree) = units.head

    // Repo-relative, forward-slashed: an absolute path would make committed expectations
    // machine-specific and the diff gate would fail on every checkout.
    val source = repoRoot.relativize(absolute).toString.replace('\\', '/')

    val diagnostics = (lexerErrors.toList ++ parserErrors.toList)
      .map(toDiagnostic(_, repoRoot))
      .sortBy(d => (d.line, d.col, d.kind))

    Projection(source, printTree(tree), diagnostics)
  }

  def formatJson(p: Projection, upstreamCommit: String, oracleSha256: String): String = {
    val diags = p.diagnostics
      .map(d => s"""      {"kind":"${esc(d.kind)}","line":${d.line},"col":${d.col},"message":"${esc(d.message)}"}""")
      .mkString(",\n")
    val diagBlock = if (p.diagnostics.isEmpty) "[]" else s"[\n$diags\n    ]"

    s"""{
       |  "schemaVersion": 1,
       |  "generatedBy": "flix.spec.ProjectionExtractor",
       |  "toolVersion": "$ToolVersion",
       |  "upstreamCommit": "$upstreamCommit",
       |  "oracleSha256": "$oracleSha256",
       |  "units": [
       |    {
       |      "source": "${esc(p.source)}",
       |      "diagnostics": $diagBlock,
       |      "tree": ${p.tree}
       |    }
       |  ]
       |}
       |""".stripMargin
  }

  // -------------------------------------------------------------------- main

  private val Usage =
    """usage: ProjectionExtractor [--out <dir>] <path-to-flix-file>...
      |
      |  default     print the projected tree for one file to stdout
      |  --out <dir> write <dir>/<fixture-name>.json for each input instead
      |""".stripMargin

  def main(args: Array[String]): Unit = {
    val outIdx = args.indexOf("--out")
    if (outIdx >= 0 && outIdx + 1 >= args.length) {
      System.err.println(s"--out requires a directory argument\n\n$Usage")
      sys.exit(2)
    }
    val outDir = if (outIdx >= 0) Some(Paths.get(args(outIdx + 1))) else None
    // Guard on outIdx >= 0: with no --out, indexOf returns -1 and outIdx + 1 is 0, which would
    // silently drop the first file argument.
    val consumed = if (outIdx >= 0) Set(outIdx, outIdx + 1) else Set.empty[Int]
    val files = args.zipWithIndex
      .filterNot { case (_, i) => consumed.contains(i) }
      .map(_._1)
      .filterNot(_.startsWith("--"))

    if (files.isEmpty) {
      System.err.println(Usage)
      sys.exit(2)
    }

    require(Files.exists(OracleJar), s"FATAL: missing $OracleJar -- run tools/oracle/fetch.sh")
    val pin = Files.readString(PinFile, StandardCharsets.UTF_8)
    val upstreamCommit =
      """"commit"\s*:\s*"([^"]*)"""".r.findFirstMatchIn(pin).map(_.group(1)).get
    val oracleSha256 = TreeKindExtractor.fileDigest(OracleJar)

    val repoRoot = Paths.get("").toAbsolutePath.normalize()

    files.sorted.foreach { f =>
      val json = formatJson(project(Paths.get(f), repoRoot), upstreamCommit, oracleSha256)
      outDir match {
        case Some(dir) =>
          Files.createDirectories(dir)
          val name = Paths.get(f).getFileName.toString.stripSuffix(".flix") + ".json"
          Files.writeString(dir.resolve(name), json, StandardCharsets.UTF_8)
        case None => print(json)
      }
    }

    outDir.foreach(dir => println(s"Wrote ${files.length} projected trees to $dir"))
  }
}
