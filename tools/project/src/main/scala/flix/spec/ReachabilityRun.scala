package flix.spec

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.{ChangeSet, SyntaxTree, Token}
import ca.uwaterloo.flix.language.ast.shared.{AvailableClasses, Input, SecurityContext}
import ca.uwaterloo.flix.language.phase.{Lexer, Parser2, Reader}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** Parses the whole pinned corpus and records three things the fixture suite cannot establish on its own
  * (implementation plan section 1).
  *
  *   - **TreeKind reachability** -- which tree kinds the reference parser actually emits;
  *   - **TokenKind reachability** -- the same question for the lexical vocabulary, which matters for consumers that
  *     have no parse tree at all;
  *   - **Losslessness at corpus scale** -- whether concatenating every token's text reproduces each source file,
  *     ignoring whitespace.
  *
  * All three are **oracle-free**: they compare the parser's output against the corpus input, not against expectations
  * derived from the parser. A derived suite cannot falsify the reference compiler, but these can, because none of them
  * needs an independent specification of Flix.
  *
  * A kind reported unreachable is not necessarily dead. It may be reachable only from input the corpus does not
  * contain. The claim made here is exactly the measured one: not emitted anywhere in the corpus at this pin. Deciding
  * *why* needs the source audit recorded in `docs/CONFORMANCE.md`.
  */
object ReachabilityRun {

  val ToolVersion = "1.1.0"

  private val OracleJar = Paths.get(".oracle/flix.jar")
  private val PinFile = Paths.get("pin.json")
  private val DefaultCorpus = Paths.get(".oracle/flix-repo")

  /** Per-file accumulators, threaded through one corpus walk so the three measurements cost one parse rather than
    * three.
    */
  private final class Tally {
    val kinds: mutable.Map[String, Long] = mutable.Map.empty
    val tokens: mutable.Map[String, Long] = mutable.Map.empty
    val text: StringBuilder = new StringBuilder
  }

  private def collect(tree: SyntaxTree.Tree, t: Tally): Unit = {
    val name = TreeKindNaming.qualifiedNameOf(tree.kind)
    t.kinds.update(name, t.kinds.getOrElse(name, 0L) + 1L)
    tree.children.foreach {
      case sub: SyntaxTree.Tree => collect(sub, t)
      case tok: Token =>
        val tn = TreeKindNaming.simpleName(tok.kind.getClass.getName)
        t.tokens.update(tn, t.tokens.getOrElse(tn, 0L) + 1L)
        t.text.append(tok.text)
      case _ => ()
    }
  }

  /** Normalises a source or reconstruction for comparison.
    *
    * Two things are removed, and only two:
    *
    *   - **whitespace**, which Flix does not emit as tokens at all;
    *   - the **`$` escape marker** before a name. `Lexer.scala:519-521` moves past it explicitly ("Don't include the $
    *     sign in the name"), so in `x.$and(y)` the token spans `and` and the `$` belongs to no token. It is a marker,
    *     like whitespace, not content.
    *
    * The `$` rule is deliberately narrow -- only when followed by a name character -- so string interpolation
    * (`${expr}`, where `$` precedes `{`) still has to round-trip, and a genuinely dropped `$` inside a string literal
    * would still be caught.
    */
  private def squeeze(s: String): String =
    s.replaceAll("\\s+", "").replaceAll("\\$(?=[A-Za-z_])", "")

  /** Result of parsing one corpus file. */
  private final case class FileResult(readable: Boolean, cleanParse: Boolean, lossless: Boolean)

  private def parseOne(file: Path, t: Tally): FileResult = {
    implicit val flix: Flix = new Flix()
    flix.threadPool = new java.util.concurrent.ForkJoinPool(1)

    val inputs = List(Input.RealFile(file, SecurityContext.Plain))
    val (afterReader, readerErrors) = Reader.run(inputs, AvailableClasses.empty)
    if (readerErrors.nonEmpty) return FileResult(readable = false, cleanParse = false, lossless = false)

    val (afterLexer, _) = Lexer.run(afterReader, Map.empty, ChangeSet.Everything)
    val (afterParser, parserErrors) = Parser2.run(afterLexer, SyntaxTree.empty, ChangeSet.Everything)

    val local = new Tally
    afterParser.units.foreach { case (_, tree) => collect(tree, local) }

    local.kinds.foreach { case (k, n) => t.kinds.update(k, t.kinds.getOrElse(k, 0L) + n) }
    local.tokens.foreach { case (k, n) => t.tokens.update(k, t.tokens.getOrElse(k, 0L) + n) }

    val onDisk = squeeze(Files.readString(file, StandardCharsets.UTF_8))
    FileResult(readable = true, cleanParse = parserErrors.isEmpty, lossless = squeeze(local.text.toString) == onDisk)
  }

  private def obj(sb: StringBuilder, label: String, entries: List[(String, Long)]): Unit = {
    sb.append(s"""  "$label": {""")
    if (entries.isEmpty) sb.append("},\n")
    else {
      sb.append("\n")
      entries.zipWithIndex.foreach { case ((k, v), i) =>
        sb.append(s"""    "$k": $v${if (i < entries.length - 1) "," else ""}\n""")
      }
      sb.append("  },\n")
    }
  }

  private def arr(sb: StringBuilder, label: String, items: List[String], last: Boolean = false): Unit = {
    val tail = if (last) "\n" else ",\n"
    if (items.isEmpty) sb.append(s"""  "$label": []$tail""")
    else {
      sb.append(s"""  "$label": [\n""")
      items.zipWithIndex.foreach { case (k, i) =>
        sb.append(s"""    "$k"${if (i < items.length - 1) "," else ""}\n""")
      }
      sb.append(s"  ]$tail")
    }
  }

  def main(args: Array[String]): Unit = {
    val corpusRoot = args.headOption.map(Paths.get(_)).getOrElse(DefaultCorpus)
    require(Files.isDirectory(corpusRoot), s"FATAL: corpus not found at $corpusRoot -- run ./corpus/fetch first")
    require(Files.exists(OracleJar), s"FATAL: missing $OracleJar -- run tools/oracle/fetch.sh")

    val pin = Json.parseFile(PinFile)
    val upstreamCommit = pin("upstream")("commit").asString
    val oracleSha256 = TreeKindExtractor.fileDigest(OracleJar)

    val files = Files
      .walk(corpusRoot)
      .iterator()
      .asScala
      .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".flix"))
      .toList
      .sortBy(_.toString)

    require(files.nonEmpty, s"FATAL: no .flix files under $corpusRoot")

    val tally = new Tally
    var clean = 0
    var unreadable = 0
    val lossy = mutable.ListBuffer.empty[String]

    files.foreach { f =>
      // Flix's own corpus contains deliberately invalid sources; a parse error is data, not a
      // failure. Recovery still yields a tree, so its kinds are collected either way.
      val r = parseOne(f, tally)
      if (!r.readable) unreadable += 1
      else {
        if (r.cleanParse) clean += 1
        // Losslessness is only meaningful for a file the parser accepted. Error recovery may
        // legitimately discard text it could not attach, so a lossy *invalid* file is not a
        // finding; a lossy *valid* one is.
        if (r.cleanParse && !r.lossless) lossy += corpusRoot.relativize(f).toString
      }
    }

    val treeInv = TreeKindExtractor.extractTreeKinds(OracleJar).map(_.name)
    val tokInv = TokenKindExtractor.extractTokenKinds(OracleJar).map(_.name)

    val unknownKinds = tally.kinds.keySet.toList.filterNot(treeInv.contains).sorted
    require(unknownKinds.isEmpty, s"FATAL: kinds emitted but absent from the inventory: $unknownKinds")
    val unknownTokens = tally.tokens.keySet.toList.filterNot(tokInv.contains).sorted
    require(unknownTokens.isEmpty, s"FATAL: tokens emitted but absent from the inventory: $unknownTokens")

    val reached = treeInv.filter(tally.kinds.contains).sorted
    val unreachable = treeInv.filterNot(tally.kinds.contains).sorted
    val tokReached = tokInv.filter(tally.tokens.contains).sorted
    val tokUnreachable = tokInv.filterNot(tally.tokens.contains).sorted

    val sb = new StringBuilder
    sb.append("{\n")
    sb.append("  \"schemaVersion\": 1,\n")
    sb.append("  \"generatedBy\": \"flix.spec.ReachabilityRun\",\n")
    sb.append(s"""  "toolVersion": "$ToolVersion",\n""")
    sb.append(s"""  "upstreamCommit": "$upstreamCommit",\n""")
    sb.append(s"""  "oracleSha256": "$oracleSha256",\n""")
    sb.append(s"""  "corpusFiles": ${files.length},\n""")
    sb.append(s"""  "filesUnreadable": $unreadable,\n""")
    sb.append(s"""  "filesParsedWithoutError": $clean,\n""")
    sb.append(s"""  "filesLosslessOfCleanlyParsed": ${clean - lossy.length},\n""")
    sb.append(s"""  "treeKindCount": ${treeInv.length},\n""")
    sb.append(s"""  "reachableCount": ${reached.length},\n""")
    sb.append(s"""  "unreachableCount": ${unreachable.length},\n""")
    sb.append(s"""  "tokenKindCount": ${tokInv.length},\n""")
    sb.append(s"""  "tokenReachableCount": ${tokReached.length},\n""")
    sb.append(s"""  "tokenUnreachableCount": ${tokUnreachable.length},\n""")
    obj(sb, "reachable", reached.map(k => k -> tally.kinds(k)))
    arr(sb, "unreachable", unreachable)
    obj(sb, "tokenReachable", tokReached.map(k => k -> tally.tokens(k)))
    arr(sb, "tokenUnreachable", tokUnreachable)
    arr(sb, "lossyFiles", lossy.toList.sorted, last = true)
    sb.append("}\n")

    val out = Paths.get("ast/reachability.json")
    Option(out.getParent).foreach(Files.createDirectories(_))
    Files.writeString(out, sb.toString, StandardCharsets.UTF_8)

    println(
      s"Wrote $out over ${files.length} corpus files ($clean parsed without error):\n" +
        s"  TreeKind  ${reached.length}/${treeInv.length} reachable, ${unreachable.length} never emitted\n" +
        s"  TokenKind ${tokReached.length}/${tokInv.length} reachable, ${tokUnreachable.length} never emitted\n" +
        s"  lossless  ${clean - lossy.length}/$clean of cleanly-parsed files"
    )

    if (lossy.nonEmpty) {
      System.err.println(s"FATAL: ${lossy.length} cleanly-parsed file(s) did not reconstruct their source:")
      lossy.toList.sorted.take(20).foreach(p => System.err.println(s"  $p"))
      sys.exit(1)
    }
  }
}
