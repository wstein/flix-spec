package flix.spec

import java.nio.file.{Files, Path, Paths}

/** Where the specification data lives, and where a consumer's sources live.
  *
  * Everything the comparison reads -- `pin.json`, `ast/`, `schemas/`, `corpus/corpus.json`, the committed fixtures --
  * used to be resolved against the process's working directory. That is invisible when the tool is run by Gradle from
  * the repository root, and it is the single reason the comparison could not be run anywhere else: a consumer holding
  * the published data bundle had no way to say where it is.
  *
  * Two roots, because they answer different questions. `specRoot` is the bundle: the reference's own data, which a
  * consumer downloads and never edits. `sourceRoot` is the tree the fixtures' `source` paths are relative to, which is
  * the same bundle when checking the reference against itself and the consumer's own checkout otherwise.
  *
  * Both default to the working directory, so nothing changes for a run started from the repository root.
  */
object Spec {

  /** The two committed fixture forms, bundle-relative.
    *
    * These lived on `ProjectionExtractor`, which imports the oracle. Referencing a constant there was enough to load
    * that class, so the comparison could not run without the 39 MB jar on the classpath -- oracle-free by inspection
    * and not in fact. A directory name is not oracle knowledge; it belongs here.
    */
  val RawDir = "fixtures/raw"
  val NormalizedDir = "fixtures/expected"

  private var specRootPath: Path = Paths.get("")
  private var sourceRootPath: Path = Paths.get("")

  def specRoot: Path = specRootPath
  def sourceRoot: Path = sourceRootPath

  /** Resolves a bundle-relative path, e.g. `ast/treekind.json`. */
  def resolve(relative: String): Path = specRootPath.resolve(relative)

  /** Resolves a fixture's `source`, which is recorded repository-relative so a committed expectation is not
    * machine-specific.
    */
  def resolveSource(relative: String): Path = sourceRootPath.resolve(relative)

  /** Sets the roots, failing loudly rather than resolving against the wrong tree.
    *
    * A missing `pin.json` under `specRoot` is the tell that someone pointed this at a checkout rather than at a bundle,
    * or at the wrong directory entirely, and the failure is far clearer here than as a `NoSuchFileException` thrown
    * midway through a comparison.
    */
  def configure(spec: Option[String], source: Option[String]): Unit = {
    spec.foreach { s =>
      val p = Paths.get(s).toAbsolutePath.normalize()
      if (!Files.isDirectory(p)) {
        System.err.println(s"FATAL: --spec-root '$s' is not a directory")
        sys.exit(ExitCode.InvalidInput)
      }
      if (!Files.isRegularFile(p.resolve("pin.json"))) {
        System.err.println(s"FATAL: --spec-root '$s' has no pin.json, so it is not a flix-spec data bundle")
        sys.exit(ExitCode.InvalidInput)
      }
      specRootPath = p
      // A bundle is self-contained: unless told otherwise, its fixtures' sources are inside it.
      sourceRootPath = p
    }
    source.foreach { s =>
      val p = Paths.get(s).toAbsolutePath.normalize()
      if (!Files.isDirectory(p)) {
        System.err.println(s"FATAL: --source-root '$s' is not a directory")
        sys.exit(ExitCode.InvalidInput)
      }
      sourceRootPath = p
    }
  }
}

/** Stable exit codes, so a caller can tell the three outcomes apart without parsing output.
  *
  * A conformance failure and a malformed invocation are not the same event: the first is the tool working, the second
  * is the tool being unable to start. Collapsing both into 1 makes a CI script unable to distinguish "your parser
  * disagrees" from "you passed the wrong flag".
  */
object ExitCode {
  val Pass = 0
  val ConformanceFailure = 1
  val InvalidInput = 2
}
