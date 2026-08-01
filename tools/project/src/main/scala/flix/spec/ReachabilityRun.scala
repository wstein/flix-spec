package flix.spec

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.{ChangeSet, SyntaxTree, Token}
import ca.uwaterloo.flix.language.ast.shared.{AvailableClasses, Input, SecurityContext}
import ca.uwaterloo.flix.language.phase.{Lexer, Parser2, Reader}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** Parses the whole pinned corpus and records which `TreeKind`s the reference parser actually emits (implementation
  * plan section 1, "Reachability").
  *
  * This is the only artifact here that states a fact about *Flix* rather than about our parsers. A derived suite cannot
  * falsify the reference compiler, but reachability needs no independent specification: either the parser emits a kind
  * somewhere in 873 files, or it does not.
  *
  * A kind reported unreachable is not necessarily dead. It may be reachable only from input the corpus does not
  * contain. The claim made here is exactly the measured one: not emitted anywhere in the corpus at this pin.
  */
object ReachabilityRun {

  val ToolVersion = "1.0.0"

  private val OracleJar = Paths.get(".oracle/flix.jar")
  private val PinFile = Paths.get("pin.json")
  private val DefaultCorpus = Paths.get(".oracle/flix-repo")

  private def collect(tree: SyntaxTree.Tree, counts: mutable.Map[String, Long]): Unit = {
    val name = TreeKindNaming.qualifiedNameOf(tree.kind)
    counts.update(name, counts.getOrElse(name, 0L) + 1L)
    tree.children.foreach {
      case sub: SyntaxTree.Tree => collect(sub, counts)
      case _: Token             => ()
      case _                    => ()
    }
  }

  /** Parses one file, returning its kind counts, or None if the file could not be read. */
  private def parseOne(file: Path, counts: mutable.Map[String, Long]): Boolean = {
    implicit val flix: Flix = new Flix()
    flix.threadPool = new java.util.concurrent.ForkJoinPool(1)

    val inputs = List(Input.RealFile(file, SecurityContext.Plain))
    val (afterReader, readerErrors) = Reader.run(inputs, AvailableClasses.empty)
    if (readerErrors.nonEmpty) return false

    val (afterLexer, _) = Lexer.run(afterReader, Map.empty, ChangeSet.Everything)
    val (afterParser, parserErrors) = Parser2.run(afterLexer, SyntaxTree.empty, ChangeSet.Everything)
    afterParser.units.foreach { case (_, tree) => collect(tree, counts) }
    parserErrors.isEmpty
  }

  def main(args: Array[String]): Unit = {
    val corpusRoot = args.headOption.map(Paths.get(_)).getOrElse(DefaultCorpus)
    require(
      Files.isDirectory(corpusRoot),
      s"FATAL: corpus not found at $corpusRoot -- run ./corpus/fetch first"
    )
    require(Files.exists(OracleJar), s"FATAL: missing $OracleJar -- run tools/oracle/fetch.sh")

    val pin = Files.readString(PinFile, StandardCharsets.UTF_8)
    val upstreamCommit =
      """"commit"\s*:\s*"([^"]*)"""".r.findFirstMatchIn(pin).map(_.group(1)).get
    val oracleSha256 = TreeKindExtractor.fileDigest(OracleJar)

    val files = Files
      .walk(corpusRoot)
      .iterator()
      .asScala
      .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".flix"))
      .toList
      .sortBy(_.toString)

    require(files.nonEmpty, s"FATAL: no .flix files under $corpusRoot")

    val counts = mutable.Map.empty[String, Long]
    var clean = 0
    files.foreach { f =>
      // Flix's own corpus contains deliberately invalid sources; a parse error is data, not a
      // failure. Recovery still yields a tree, so its kinds are collected either way.
      if (parseOne(f, counts)) clean += 1
    }

    val inventory = TreeKindExtractor.extractTreeKinds(OracleJar).map(_.name)
    val unreachable = inventory.filterNot(counts.contains).sorted
    val unknown = counts.keySet.toList.filterNot(inventory.contains).sorted
    require(unknown.isEmpty, s"FATAL: kinds emitted but absent from the inventory: $unknown")

    val reached = inventory.filter(counts.contains).sorted
    val sb = new StringBuilder
    sb.append("{\n")
    sb.append("  \"schemaVersion\": 1,\n")
    sb.append("  \"generatedBy\": \"flix.spec.ReachabilityRun\",\n")
    sb.append(s"""  "toolVersion": "$ToolVersion",\n""")
    sb.append(s"""  "upstreamCommit": "$upstreamCommit",\n""")
    sb.append(s"""  "oracleSha256": "$oracleSha256",\n""")
    sb.append(s"""  "corpusFiles": ${files.length},\n""")
    sb.append(s"""  "filesParsedWithoutError": $clean,\n""")
    sb.append(s"""  "treeKindCount": ${inventory.length},\n""")
    sb.append(s"""  "reachableCount": ${reached.length},\n""")
    sb.append(s"""  "unreachableCount": ${unreachable.length},\n""")
    sb.append("  \"reachable\": {\n")
    reached.zipWithIndex.foreach { case (k, i) =>
      val comma = if (i < reached.length - 1) "," else ""
      sb.append(s"""    "$k": ${counts(k)}$comma\n""")
    }
    sb.append("  },\n")
    sb.append("  \"unreachable\": [\n")
    unreachable.zipWithIndex.foreach { case (k, i) =>
      val comma = if (i < unreachable.length - 1) "," else ""
      sb.append(s"""    "$k"$comma\n""")
    }
    sb.append("  ]\n")
    sb.append("}\n")

    val out = Paths.get("ast/reachability.json")
    Option(out.getParent).foreach(Files.createDirectories(_))
    Files.writeString(out, sb.toString, StandardCharsets.UTF_8)
    println(
      s"Wrote $out: ${reached.length}/${inventory.length} kinds reachable across " +
        s"${files.length} corpus files ($clean parsed without error); " +
        s"${unreachable.length} never emitted"
    )
  }
}
