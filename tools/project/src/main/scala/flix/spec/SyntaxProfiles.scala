package flix.spec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest

/** Deliberately bounded compatibility profiles; never compared to the current vocabulary. */
object SyntaxProfiles {
  val Versions =
    List("0.75.0", "0.75.1", "0.75.2", "0.75.3", "0.76.0", "0.76.1", "0.76.2", "0.77.0", "0.78.0", "0.79.0")
  def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
  def requireDigest(file: Path, expected: String): Unit =
    require(sha256(Files.readAllBytes(file)) == expected, s"digest mismatch: $file")
  private def profiles(root: Path): List[Json] =
    Json.parseFile(root.resolve("profiles/profiles.json"))("profiles").asArray
  def select(root: Path, version: String): Json = {
    require(Versions.contains(version), s"unknown exact syntax profile: $version")
    profiles(root)
      .find(_("version").asString == version)
      .getOrElse(throw new IllegalArgumentException(s"missing profile $version"))
  }
  private def local(root: Path, value: String): Path = {
    val base = root.toAbsolutePath.normalize()
    val path = base.resolve(value).normalize()
    require(path.startsWith(base) && value.startsWith("profiles/"), s"profile path escapes profiles: $value")
    path
  }
  def validate(root: Path): Int = {
    val doc = Json.parseFile(root.resolve("profiles/profiles.json"))
    val schema = Json.parseFile(root.resolve("schemas/syntax-profiles.schema.json"))
    val errors = new SchemaValidator.Errors
    SchemaValidator.check(doc, schema, schema, "profiles/profiles.json", errors)
    require(errors.toList.isEmpty, errors.toList.mkString("\n"))
    val entries = profiles(root)
    val sourcePaths = Set("ast/SyntaxTree.scala", "ast/TokenKind.scala", "phase/Parser2.scala", "phase/Lexer.scala")
      .map("main/src/ca/uwaterloo/flix/language/" + _)
    val caseIds = Set(
      "baseline",
      "law",
      "lawful",
      "effect-type-parameter",
      "tight-use",
      "complex-row-tail",
      "comments",
      "invalid-lexer",
      "invalid-parser"
    )
    require(entries.map(_("version").asString) == Versions, "exact ten ordered release profiles required")
    entries.foreach { p =>
      require(p("sources").asObject.keySet == sourcePaths, "exact four vocabulary source identities required")
      require(p("sources").asObject.values.forall(_.asString.matches("[0-9a-f]{40}")), "invalid source blob identity")
      require(p("cases").asArray.map(_("id").asString).toSet == caseIds, "exact nine bounded cases required")
      require(
        p("oracleUrl").asString == "https://github.com/flix/flix/releases/download/" + p("tag").asString + "/flix.jar",
        "oracle URL/tag mismatch"
      )
      require(p("tag").asString == "v" + p("version").asString, "profile tag/version mismatch")
      p("cases").asArray.foreach { c =>
        require(
          c("expected").asString == "profiles/expected/" + p("version").asString + "/" + c("id").asString + ".json",
          "cross-profile expectation"
        )
        require(c("source").asString == "profiles/sources/" + c("id").asString + ".flix", "case/source mismatch")
        requireDigest(local(root, c("source").asString), c("sourceSha256").asString)
        requireDigest(local(root, c("expected").asString), c("expectedSha256").asString)
        val expected = Json.parseFile(local(root, c("expected").asString))
        val snapshotErrors = new SchemaValidator.Errors
        SchemaValidator.check(
          expected,
          schema("definitions")("snapshot"),
          schema,
          c("expected").asString,
          snapshotErrors
        )
        require(snapshotErrors.isEmpty, snapshotErrors.toList.mkString("\n"))
        require(expected("tree")("kind").asString == "Root", "profile must contain a measured nonempty Root")
        require(expected("tree")("children").asArray.nonEmpty, "empty oracle tree")
        require(expected("accepted") == Json.JBool(expected("diagnostics").asArray.isEmpty), "inconsistent acceptance")
        if (Set("invalid-lexer", "invalid-parser").contains(c("id").asString))
          require(expected("accepted") == Json.JBool(false), "invalid sentinel must have diagnostics")
      }
      require(
        p("cases").asArray.map(_("id").asString).distinct.size == p("cases").asArray.size,
        "duplicate profile case"
      )
    }
    entries.size
  }
  def probe(root: Path, version: String, jar: Path, source: Path): String = {
    val p = select(root, version)
    requireDigest(jar, p("oracleSha256").asString)
    val classes = Paths.get(SyntaxProfilesWorker.getClass.getProtectionDomain.getCodeSource.getLocation.toURI)
    val javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val process = new ProcessBuilder(
      javaBin,
      "-cp",
      classes.toString + java.io.File.pathSeparator + jar.toAbsolutePath,
      "flix.spec.SyntaxProfilesWorker",
      source.toAbsolutePath.toString
    ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val output = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    require(process.waitFor() == 0, s"profile worker failed: $version")
    output
  }
  def verify(root: Path, version: String, jar: Path): Int = {
    validate(root)
    val cases = select(root, version)("cases").asArray
    cases.foreach { c =>
      val actual = probe(root, version, jar, local(root, c("source").asString))
      require(
        sha256(actual.getBytes(StandardCharsets.UTF_8)) == c("expectedSha256").asString,
        s"syntax profile mismatch: $version/${c("id").asString}"
      )
    }
    cases.size
  }
  def main(args: Array[String]): Unit = {
    val root = Paths.get("").toAbsolutePath.normalize()
    val command = args.toList match {
      case List("--profile", v, "--verify", jar)                     => List("verify", v, jar)
      case List("--profile", v, "--oracle", jar, "--source", source) => List("probe", v, jar, source)
      case other                                                     => other
    }
    command match {
      case List("validate") | List("--check") => println(s"${validate(root)} syntax profiles validated")
      case List("list")                       => Versions.foreach(println)
      case List("verify", version, jar) => println(s"${verify(root, version, Paths.get(jar))} cases verified: $version")
      case List("probe", version, jar, source) => print(probe(root, version, Paths.get(jar), Paths.get(source)))
      case _ =>
        throw new IllegalArgumentException(
          "syntaxProfiles list | validate | verify VERSION JAR | probe VERSION JAR SOURCE"
        )
    }
  }
}
