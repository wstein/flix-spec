package flix.spec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.jar.JarFile
import scala.jdk.CollectionConverters._

/** One entry in `ast/tokenkind.json`. */
case class TokenKindInfo(name: String, form: String)

/** Generates `ast/tokenkind.json` by enumerating the sealed `TokenKind` hierarchy on the pinned oracle jar's classpath.
  *
  * The token vocabulary is the contract for **lexical** consumers -- syntax highlighters and TextMate grammars -- which
  * have no parse tree and so can never consume `fixtures/expected/`. Publishing it here replaces the alternative those
  * consumers otherwise reach for: scraping `Lexer.scala`'s `private val Keywords` and friends as text, which is the
  * technique this repository bans and which cannot be checked against a digest.
  *
  * Unlike [[TreeKindNaming]]'s subject, `TokenKind` is a **flat** hierarchy: 159 case objects plus the single case
  * class `Err`, with no sub-traits, so names need no qualification and cannot collide.
  */
object TokenKindExtractor {

  /** Bumped whenever this generator's output format or selection logic changes. */
  val ToolVersion = "1.0.0"

  private val OracleJar = Paths.get(".oracle/flix.jar")
  private val PinFile = Paths.get("pin.json")
  private val TokenKindClass = "ca.uwaterloo.flix.language.ast.TokenKind"
  private val EntryPrefix = "ca/uwaterloo/flix/language/ast/TokenKind"

  /** Last `$`-segment of a binary name, after stripping the trailing `$` of a Scala object. */
  private def simpleName(binaryName: String): String = {
    val trimmed = binaryName.stripSuffix("$")
    trimmed.substring(trimmed.lastIndexOf('$') + 1)
  }

  def extractTokenKinds(jar: Path): List[TokenKindInfo] = {
    val loader = getClass.getClassLoader
    val tokenKind = loader.loadClass(TokenKindClass)

    val jf = new JarFile(jar.toFile)
    try {
      val kinds = jf
        .entries()
        .asScala
        .map(_.getName)
        .filter(n => n.startsWith(EntryPrefix) && n.endsWith(".class"))
        .flatMap { n =>
          val binary = n.stripSuffix(".class").replace('/', '.')
          try Some(loader.loadClass(binary))
          catch { case _: Throwable => None }
        }
        .filter(c => tokenKind.isAssignableFrom(c) && !c.isInterface && c != tokenKind)
        .map(c => TokenKindInfo(simpleName(c.getName), TreeKindNaming.formOf(c)))
        .toList
        .distinctBy(_.name)
        .sortBy(_.name)

      // Self-assertion per plan section 4: every check is on our own output.
      require(kinds.nonEmpty, "FATAL: no TokenKinds found -- is the oracle jar on the classpath?")
      require(kinds.map(_.name).distinct.length == kinds.length, "FATAL: duplicate TokenKind names")
      require(kinds.forall(_.name.nonEmpty), "FATAL: empty TokenKind name")
      kinds
    } finally jf.close()
  }

  def calculateDigest(kinds: List[TokenKindInfo]): String =
    TreeKindExtractor.sha256Hex(kinds.map(_.name).sorted.mkString("\n").getBytes(StandardCharsets.UTF_8))

  def formatJson(
      kinds: List[TokenKindInfo],
      digest: String,
      upstreamCommit: String,
      oracleSha256: String
  ): String = {
    val sb = new StringBuilder
    sb.append("{\n")
    sb.append("  \"schemaVersion\": 1,\n")
    sb.append("  \"generatedBy\": \"flix.spec.TokenKindExtractor\",\n")
    sb.append(s"""  "toolVersion": "$ToolVersion",\n""")
    sb.append(s"""  "upstreamCommit": "$upstreamCommit",\n""")
    sb.append(s"""  "oracleSha256": "$oracleSha256",\n""")
    sb.append(s"""  "tokenKindCount": ${kinds.length},\n""")
    sb.append(s"""  "tokenKindDigest": "$digest",\n""")
    sb.append("  \"kinds\": [\n")
    kinds.zipWithIndex.foreach { case (k, idx) =>
      val comma = if (idx < kinds.length - 1) "," else ""
      sb.append("    {\n")
      sb.append(s"""      "name": "${k.name}",\n""")
      sb.append(s"""      "form": "${k.form}"\n""")
      sb.append(s"    }$comma\n")
    }
    sb.append("  ]\n")
    sb.append("}\n")
    sb.toString
  }

  private val Usage =
    """usage: TokenKindExtractor [--propose] [<output-path>]
      |
      |  default    assert count and digest against pin.json, then write the inventory
      |  --propose  report the count and digest without asserting or writing
      |""".stripMargin

  def main(args: Array[String]): Unit = {
    val propose = args.contains("--propose")
    val positional = args.filterNot(_.startsWith("--"))
    val unknown = args.filter(a => a.startsWith("--") && a != "--propose")
    if (unknown.nonEmpty) {
      System.err.println(s"unknown option: ${unknown.mkString(" ")}\n\n$Usage")
      sys.exit(2)
    }

    require(Files.exists(OracleJar), s"FATAL: missing $OracleJar -- run tools/oracle/fetch.sh")
    require(Files.exists(PinFile), s"FATAL: missing $PinFile")

    val pin = Json.parseFile(PinFile)
    val upstreamCommit = pin("upstream")("commit").asString
    val expectedOracle = pin("oracleArtifact")("sha256").asString

    // The oracle must be the artifact pin.json names, checked before anything is derived from it.
    val oracleSha256 = TreeKindExtractor.fileDigest(OracleJar)
    require(
      oracleSha256 == expectedOracle,
      s"FATAL: oracle jar digest mismatch\n  pin.json: $expectedOracle\n  on disk:  $oracleSha256"
    )

    val kinds = extractTokenKinds(OracleJar)
    val digest = calculateDigest(kinds)

    if (propose) {
      // Discovery mode. Asserting here would be circular: a pin bump cannot know the new count or
      // digest until the new jar has been read, and reading it is what this reports.
      println(s"""  "tokenKindCount": ${kinds.length},""")
      println(s"""  "tokenKindDigest": "$digest"""")
      System.err.println(
        "Proposed values for pin.json. Nothing was written and no assertion was made.\n" +
          "Copy them into pin.json, then run generateTokenKind to regenerate under assertion."
      )
      return
    }

    val expectedCount = pin("tokenKindCount").asInt
    val expectedDigest = pin("tokenKindDigest").asString
    require(
      kinds.length == expectedCount,
      s"FATAL: expected $expectedCount TokenKinds per pin.json, got ${kinds.length}.\n" +
        "       If this is a pin bump, run with --propose and update pin.json first."
    )
    require(
      digest == expectedDigest,
      s"FATAL: name-set digest mismatch\n  pin.json: $expectedDigest\n  computed: $digest\n" +
        "       If this is a pin bump, run with --propose and update pin.json first."
    )

    val json = formatJson(kinds, digest, upstreamCommit, oracleSha256)
    positional.headOption match {
      case Some(target) =>
        val path = Paths.get(target).toAbsolutePath
        Option(path.getParent).foreach(Files.createDirectories(_))
        Files.writeString(path, json, StandardCharsets.UTF_8)
        println(s"Wrote ${kinds.length} TokenKinds to $path with digest $digest")
      case None => print(json)
    }
  }
}
