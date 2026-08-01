package flix.spec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.jar.JarFile
import scala.jdk.CollectionConverters._

/** One entry in ast/treekind.json. */
case class TreeKindInfo(
    name: String,
    `extends`: String,
    form: String
)

/** Generates ast/treekind.json by enumerating the sealed TreeKind hierarchy on the pinned oracle jar's classpath
  * (implementation plan section 4.2).
  *
  * Enumeration comes from jar entries; every *decision* is reflective. Name-based selection is wrong here and
  * measurably so: on the pinned jar the pattern `SyntaxTree$*` matches 213 classes and `SyntaxTree$TreeKind$*` matches
  * 206, while the reflective filter yields exactly 192. The surplus is the six sub-traits (each as interface plus
  * companion object), ErrorTree's companion, and TreeKind$ itself.
  */
object TreeKindExtractor {

  /** Bumped whenever this generator's output format or selection logic changes. */
  val ToolVersion = "1.0.0"

  private val OracleJar = Paths.get(".oracle/flix.jar")
  private val PinFile = Paths.get("pin.json")
  private val TreeKindClass = "ca.uwaterloo.flix.language.ast.SyntaxTree$TreeKind"
  private val EntryPrefix = "ca/uwaterloo/flix/language/ast/SyntaxTree$"

  // ---------------------------------------------------------------- pin.json

  /** Minimal scalar lookup over pin.json. This is our own generated JSON with a fixed shape, not foreign source text; a
    * full parser would mean either a new dependency or relying on a library that happens to be bundled inside the
    * oracle jar, and both are worse trades.
    */
  private def pinString(json: String, key: String, after: Option[String] = None): String = {
    val haystack = after match {
      case Some(marker) =>
        val i = json.indexOf(marker)
        require(i >= 0, s"pin.json is missing the '$marker' section")
        json.substring(i)
      case None => json
    }
    val m = s""""$key"\\s*:\\s*"([^"]*)"""".r.findFirstMatchIn(haystack)
    require(m.isDefined, s"pin.json is missing a string value for '$key'")
    m.get.group(1)
  }

  private def pinInt(json: String, key: String): Int = {
    val m = s""""$key"\\s*:\\s*(\\d+)""".r.findFirstMatchIn(json)
    require(m.isDefined, s"pin.json is missing an integer value for '$key'")
    m.get.group(1).toInt
  }

  // ------------------------------------------------------------- enumeration

  /** Last `$`-segment of a binary name: `...SyntaxTree$TreeKind$Expr$Apply$` -> `Apply`. */
  private def simpleName(binaryName: String): String =
    binaryName.stripSuffix("$").split('$').last

  /** Qualified name, built from the *type hierarchy* rather than lexical nesting.
    *
    * These genuinely disagree: `case object DerivationList extends Type` is declared at TreeKind top level
    * (SyntaxTree.scala:98) but extends `Type`, so binary nesting says `DerivationList` while the type hierarchy says
    * `Type.DerivationList`. Reflection was chosen over source parsing precisely so the hierarchy wins, and `name` must
    * agree with `extends` by construction rather than by coincidence.
    */
  private def qualifiedName(parent: String, binaryName: String): String =
    if (parent == "TreeKind") simpleName(binaryName) else s"$parent.${simpleName(binaryName)}"

  /** The sub-trait a kind belongs to (Decl, Expr, Type, ...), or TreeKind for top-level kinds. */
  private def parentOf(c: Class[_], treeKind: Class[_]): String = {
    val subTrait = c.getInterfaces.find(i => i != treeKind && treeKind.isAssignableFrom(i))
    subTrait.map(i => simpleName(i.getName)).getOrElse("TreeKind")
  }

  /** case object or case class, decided by the presence of Scala's MODULE$ field -- never by matching against a
    * hardcoded name.
    */
  private def formOf(c: Class[_]): String =
    if (c.getFields.exists(_.getName == "MODULE$")) "case-object" else "case-class"

  def extractTreeKinds(jar: Path): List[TreeKindInfo] = {
    val loader = getClass.getClassLoader
    val treeKind = loader.loadClass(TreeKindClass)

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
        .filter(c => treeKind.isAssignableFrom(c) && !c.isInterface && c != treeKind)
        .map { c =>
          val parent = parentOf(c, treeKind)
          TreeKindInfo(qualifiedName(parent, c.getName), parent, formOf(c))
        }
        .toList
        .distinctBy(_.name)
        .sortBy(_.name)

      // Self-assertion per plan section 4: every check is on our own output.
      require(kinds.nonEmpty, "FATAL: no TreeKinds found -- is the oracle jar on the classpath?")
      require(
        kinds.map(_.name).distinct.length == kinds.length,
        "FATAL: duplicate TreeKind names"
      )

      // Every parent must be TreeKind itself or a real sub-trait of it, resolved reflectively.
      val subTraits = kinds.map(_.`extends`).toSet - "TreeKind"
      subTraits.foreach { name =>
        val binary = s"$TreeKindClass$$${name.replace('.', '$')}"
        val c = loader.loadClass(binary)
        require(
          c.isInterface && treeKind.isAssignableFrom(c),
          s"FATAL: declared parent '$name' is not a sub-trait of TreeKind"
        )
      }
      kinds
    } finally jf.close()
  }

  // ----------------------------------------------------------------- digests

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

  def calculateDigest(kinds: List[TreeKindInfo]): String =
    sha256(kinds.map(_.name).sorted.mkString("\n").getBytes(StandardCharsets.UTF_8))

  def fileDigest(p: Path): String = sha256(Files.readAllBytes(p))

  // ------------------------------------------------------------------ output

  def formatJson(
      kinds: List[TreeKindInfo],
      digest: String,
      upstreamCommit: String,
      oracleSha256: String
  ): String = {
    val sb = new StringBuilder
    sb.append("{\n")
    sb.append("  \"schemaVersion\": 1,\n")
    sb.append("  \"generatedBy\": \"flix.spec.TreeKindExtractor\",\n")
    sb.append(s"""  "toolVersion": "$ToolVersion",\n""")
    sb.append(s"""  "upstreamCommit": "$upstreamCommit",\n""")
    sb.append(s"""  "oracleSha256": "$oracleSha256",\n""")
    sb.append(s"  \"treeKindCount\": ${kinds.length},\n")
    sb.append(s"""  "treeKindDigest": "$digest",\n""")
    sb.append("  \"kinds\": [\n")
    kinds.zipWithIndex.foreach { case (k, idx) =>
      val comma = if (idx < kinds.length - 1) "," else ""
      sb.append("    {\n")
      sb.append(s"""      "name": "${k.name}",\n""")
      sb.append(s"""      "extends": "${k.`extends`}",\n""")
      sb.append(s"""      "form": "${k.form}"\n""")
      sb.append(s"    }$comma\n")
    }
    sb.append("  ]\n")
    sb.append("}\n")
    sb.toString
  }

  /** Usage text for the two modes. */
  private val Usage =
    """usage: TreeKindExtractor [--propose] [<output-path>]
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

    val pin = Files.readString(PinFile, StandardCharsets.UTF_8)
    val upstreamCommit = pinString(pin, "commit", after = Some("\"upstream\""))
    val expectedOracle = pinString(pin, "sha256", after = Some("\"oracleArtifact\""))

    // The oracle must be the artifact pin.json names, checked before anything is derived from
    // it. This holds in both modes: a pin bump updates the artifact digest first, so by the time
    // TreeKinds are unknown the jar's identity is already settled.
    val oracleSha256 = fileDigest(OracleJar)
    require(
      oracleSha256 == expectedOracle,
      s"FATAL: oracle jar digest mismatch\n  pin.json: $expectedOracle\n  on disk:  $oracleSha256"
    )

    val kinds = extractTreeKinds(OracleJar)
    val digest = calculateDigest(kinds)

    if (propose) {
      // Discovery mode. Asserting here would be circular: a pin bump cannot know the new count
      // or digest until the new jar has been read, and reading it is what this reports.
      println(s"""  "treeKindCount": ${kinds.length},""")
      println(s"""  "treeKindDigest": "$digest"""")
      System.err.println(
        s"Proposed values for pin.json. Nothing was written and no assertion was made.\n" +
          s"Copy them into pin.json, then run generateTreeKind to regenerate under assertion."
      )
      return
    }

    // Assert against pin.json rather than a literal: a pin bump must update pin.json in the
    // same commit, which is the review we want (plan section 3.3).
    val expectedCount = pinInt(pin, "treeKindCount")
    val expectedDigest = pinString(pin, "treeKindDigest")
    require(
      kinds.length == expectedCount,
      s"FATAL: expected $expectedCount TreeKinds per pin.json, got ${kinds.length}.\n" +
        s"       If this is a pin bump, run with --propose and update pin.json first."
    )
    require(
      digest == expectedDigest,
      s"FATAL: name-set digest mismatch\n  pin.json: $expectedDigest\n  computed: $digest\n" +
        s"       If this is a pin bump, run with --propose and update pin.json first."
    )

    val json = formatJson(kinds, digest, upstreamCommit, oracleSha256)

    positional.headOption match {
      case Some(target) =>
        val path = Paths.get(target).toAbsolutePath
        Option(path.getParent).foreach(Files.createDirectories(_))
        Files.writeString(path, json, StandardCharsets.UTF_8)
        println(s"Wrote ${kinds.length} TreeKinds to $path with digest $digest")
      case None => print(json)
    }
  }
}
