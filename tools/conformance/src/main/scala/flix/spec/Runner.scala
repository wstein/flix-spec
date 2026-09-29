package flix.spec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/** The versioned executable interface. Comparison classes are implementation details, not a library API. */
object Runner {
  lazy val supportedText: String = {
    val stream = getClass.getResourceAsStream("/supported-schemas.json")
    require(stream != null, "runner is missing its supported schema declaration")
    try new String(stream.readAllBytes(), StandardCharsets.UTF_8)
    finally stream.close()
  }
  lazy val supported: Json = Json.parse(supportedText)

  /** Consumer input belongs to the runner, not to the independently pinned data bundle. */
  def validateMap(doc: Json, label: String): Unit = {
    checkVersion(doc, "projection-map", label)
    val stream = getClass.getResourceAsStream("/projection-map.schema.json")
    require(stream != null, "runner is missing its projection-map schema")
    val schema =
      try Json.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
      finally stream.close()
    val errors = new SchemaValidator.Errors
    SchemaValidator.check(doc, schema, schema, label, errors)
    require(errors.isEmpty, errors.toList.mkString("\n"))
  }

  /** The one version of a named schema this runner reads. Exposed so tests state the number once, here, rather than
    * repeating it wherever a report is synthesised.
    */
  def supportedVersion(name: String): Int = supported(name).asInt

  def checkVersion(doc: Json, kind: String, label: String): Unit = {
    val expected = supported(kind).asInt
    require(
      doc.get("schemaVersion").contains(Json.JNumber(BigDecimal(expected))),
      s"$label: unsupported $kind schemaVersion; runner supports $expected"
    )
  }

  private def documents(dir: Path): List[Path] = {
    require(Files.isDirectory(dir), s"missing document directory: $dir")
    val stream = Files.list(dir)
    try stream.iterator().asScala.filter(p => p.toString.endsWith(".json")).toList.sorted
    finally stream.close()
  }

  private def preflight(argv: Array[String]): Unit = {
    require(argv.length % 2 == 0, "every comparison option requires a value; use --help")
    val options = argv.grouped(2).map(a => a(0) -> a(1)).toList
    require(options.map(_._1).distinct.size == options.size, "duplicate comparison option")
    val args = options.toMap
    val root = Paths.get(args.getOrElse("--spec-root", "."))
    supported.asObject.keys.filter(_.endsWith(".json")).foreach { name =>
      checkVersion(Json.parseFile(root.resolve(name)), name, name)
    }
    val actual = Paths.get(args.getOrElse("--actual", throw new IllegalArgumentException("--actual is required")))
    (documents(root.resolve(Spec.NormalizedDir)) ++ documents(root.resolve(Spec.RawDir)) ++ documents(actual))
      .foreach(p => checkVersion(Json.parseFile(p), "projection", p.toString))
    args.get("--map").foreach { name =>
      val doc = Json.parseFile(Paths.get(name))
      validateMap(doc, name)
    }
  }

  def main(argv: Array[String]): Unit = try {
    argv.toList match {
      case List("--help") =>
        println(
          "flix-spec-runner (Java 21+)\n" +
            "  --actual DIR --spec-root DIR [--source-root DIR] [--map FILE] [--report FILE]\n" +
            "  [--accepted FILE] [--baseline N] [--recovery-baseline N] [--diagnostic-baseline N]\n" +
            "  [--depth-floor PCT] [--recovery-depth-floor PCT]\n" +
            "  --supported-schemas | --version | --help\n" +
            "  render --report FILE --html FILE\n" +
            "  init --directory DIR --consumer NAME\n" +
            "Exit codes: 0 pass, 1 conformance failure, 2 invalid input or unsupported schema."
        )
      case List("--supported-schemas") => print(supportedText)
      case List("--version") => println(Option(getClass.getPackage.getImplementationVersion).getOrElse("development"))
      case List("render", "--report", input, "--html", output) => HtmlReport.write(Paths.get(input), Paths.get(output))
      case List("init", "--directory", directory, "--consumer", consumer) =>
        Scaffold.create(Paths.get(directory), consumer)
      case _ =>
        preflight(argv)
        Conformance.main(argv)
    }
  } catch {
    case NonFatal(e) =>
      System.err.println(s"FATAL: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")
      sys.exit(ExitCode.InvalidInput)
  }
}
