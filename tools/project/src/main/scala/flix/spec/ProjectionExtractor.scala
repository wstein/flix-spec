package flix.spec

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.{ChangeSet, SourcePosition, SyntaxTree, Token, TokenKind}
import ca.uwaterloo.flix.language.ast.shared.{AvailableClasses, Input, SecurityContext}
import ca.uwaterloo.flix.language.phase.{Lexer, Parser2, Reader}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** Emits projected concrete syntax trees for `.flix` files, conforming to `schemas/projection.schema.json`
  * (implementation plan section 3.1).
  *
  * Drives `Reader.run -> Lexer.run -> Parser2.run` directly rather than `Flix.check()`. The public route returns the
  * CST for the entire compilation, standard library included -- roughly a million nodes for a one-line fixture -- so
  * every expectation would embed the stdlib. The phase pipeline parses exactly the inputs it is given.
  *
  * It emits the same parse in **two forms**, and which one a reader wants depends on the question:
  *
  *   - `raw` (`fixtures/raw/`) is what the reference produced, node for node. It is the provenance record, the input to
  *     every measurement about the reference's own vocabulary -- coverage, wrapper shape, the transparency proposer --
  *     and the authority the recovery lane compares against.
  *   - `normalized` (`fixtures/expected/`) is that tree with [[Transparency]]'s rules applied: wrappers that carry no
  *     information beyond their child are gone, and the error vocabulary is spliced out. This is the canonical tree a
  *     consumer is asked to agree with, so the main conformance lane measures structure *modulo* recovery.
  *
  * Every document says which form it is, in `form`. A projected tree whose shape depends on an unstated normalisation
  * is not comparable to anything, and this repository has already learned that lesson once about pins.
  */
object ProjectionExtractor {

  /** Bumped whenever the emitted format changes. 2.0.0: `form` added, and `--out` now writes the normalised tree. */
  val ToolVersion = "2.0.0"

  /** Where the two forms live, so nothing has to spell either directory twice. */
  val RawDir = "fixtures/raw"
  val NormalizedDir = "fixtures/expected"

  val RawForm = "raw"
  val NormalizedForm = "normalized"

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

  /** Renders one projection as a document of the given `form`.
    *
    * `tree` is passed in rather than read off `p` because the two forms differ only there: the same parse, the same
    * diagnostics, the same provenance header, one tree written verbatim and one normalised.
    */
  def formatJson(p: Projection, tree: String, form: String, upstreamCommit: String, oracleSha256: String): String = {
    val diags = p.diagnostics
      .map(d => s"""      {"kind":"${esc(d.kind)}","line":${d.line},"col":${d.col},"message":"${esc(d.message)}"}""")
      .mkString(",\n")
    val diagBlock = if (p.diagnostics.isEmpty) "[]" else s"[\n$diags\n    ]"

    s"""{
       |  "schemaVersion": 2,
       |  "generatedBy": "flix.spec.ProjectionExtractor",
       |  "toolVersion": "$ToolVersion",
       |  "form": "$form",
       |  "upstreamCommit": "$upstreamCommit",
       |  "oracleSha256": "$oracleSha256",
       |  "units": [
       |    {
       |      "source": "${esc(p.source)}",
       |      "diagnostics": $diagBlock,
       |      "tree": $tree
       |    }
       |  ]
       |}
       |""".stripMargin
  }

  // -------------------------------------------------------------------- main

  private val Usage =
    s"""usage: ProjectionExtractor [--out <dir>] [--raw-out <dir>] [--form raw|normalized] <path-to-flix-file>...
       |
       |  default          print one file's projected tree to stdout, in --form
       |  --form <form>    which tree stdout gets; defaults to $RawForm, the reference's own output
       |  --out <dir>      write <dir>/<fixture-name>.json holding the normalized tree
       |  --raw-out <dir>  write <dir>/<fixture-name>.json holding the verbatim tree
       |
       |Both --out and --raw-out may be given; the file is parsed once and written twice.
       |""".stripMargin

  private def optionValue(args: Array[String], name: String): Option[String] = {
    val i = args.indexOf(name)
    if (i < 0) None
    else if (i + 1 >= args.length) {
      System.err.println(s"$name requires an argument\n\n$Usage")
      sys.exit(2)
    } else Some(args(i + 1))
  }

  private def consumedIndices(args: Array[String], names: List[String]): Set[Int] =
    names.flatMap { name =>
      val i = args.indexOf(name)
      // Guard on i >= 0: with the option absent, indexOf returns -1 and i + 1 is 0, which would
      // silently drop the first file argument.
      if (i < 0) Nil else List(i, i + 1)
    }.toSet

  def main(args: Array[String]): Unit = {
    val options = List("--out", "--raw-out", "--form")
    val outDir = optionValue(args, "--out").map(Paths.get(_))
    val rawOutDir = optionValue(args, "--raw-out").map(Paths.get(_))
    val form = optionValue(args, "--form").getOrElse(RawForm)
    if (form != RawForm && form != NormalizedForm) {
      System.err.println(s"--form must be $RawForm or $NormalizedForm, got '$form'\n\n$Usage")
      sys.exit(2)
    }

    val consumed = consumedIndices(args, options)
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

    // Loaded once, and loaded strictly: it schema-checks itself and refuses to normalise against citations that were
    // read at a commit other than the one being extracted.
    val contract = Transparency.load()

    val repoRoot = Paths.get("").toAbsolutePath.normalize()

    files.sorted.foreach { f =>
      val p = project(Paths.get(f), repoRoot)
      val normalized = Normalizer.normalizeRendered(p.tree, contract)
      val name = Paths.get(f).getFileName.toString.stripSuffix(".flix") + ".json"

      def write(dir: Path, tree: String, formName: String): Unit = {
        Files.createDirectories(dir)
        Files.writeString(
          dir.resolve(name),
          formatJson(p, tree, formName, upstreamCommit, oracleSha256),
          StandardCharsets.UTF_8
        )
      }

      rawOutDir.foreach(write(_, p.tree, RawForm))
      outDir.foreach(write(_, normalized, NormalizedForm))
      if (rawOutDir.isEmpty && outDir.isEmpty) {
        val tree = if (form == RawForm) p.tree else normalized
        print(formatJson(p, tree, form, upstreamCommit, oracleSha256))
      }
    }

    rawOutDir.foreach(dir => println(s"Wrote ${files.length} raw projected trees to $dir"))
    outDir.foreach(dir => println(s"Wrote ${files.length} normalized projected trees to $dir"))
  }
}
