package flix.spec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import javax.xml.parsers.DocumentBuilderFactory
import scala.jdk.CollectionConverters._

/** Generates the landing page served at the repository's GitHub Pages root.
  *
  * Every fact on the page is read from a file already in this repository or from the `maven-metadata.xml` a publish
  * just wrote -- nothing here is hand-maintained prose that can go stale. That is a deliberate constraint, not a style
  * preference: `docs/CONFORMANCE.md`'s baseline table drifted once this session because a number was typed instead of
  * derived, and this page is public in a way that document was not.
  *
  * Originally a bash script templating HTML with dozens of `${VAR}` interpolations. `shellcheck` caught one real bug in
  * it -- an unused variable -- and a second, worse one survived past that: the prose read `$COVERED` while claiming to
  * describe reachability, silently correct only because the two numbers happened to be equal at the time of writing.
  * They stopped being equal before this rewrite finished. A typed function whose parameters are the facts it renders
  * cannot silently swap two of them; using the wrong one is a compile error, not a coincidence.
  *
  * Deliberately not a Sonatype Nexus / Maven Central style artifact browser with Overview / Versions / Dependents /
  * Dependencies tabs. Two of those four would be fabricated for a repository this small: "Dependencies" is empty (this
  * ships data, not code with a resolvable graph), and "Dependents" would borrow Central's authority for a claim this
  * repository cannot back with the same rigor -- there is no registry of who depends on `flix-spec`, and no usage data
  * discovered by scanning the world's POMs. One page, plainly sectioned, says only what is true.
  *
  * The page did carry a Dependents table, populated by listing `ast/projection/`. That directory is gone: a projection
  * map encodes facts about one consumer's grammar, so each now lives in the consumer's own repository. Rendering the
  * section from a directory that cannot exist would have reported "none yet" forever -- a page confidently stating the
  * opposite of the truth, which is a worse failure than omitting the section.
  */
object LandingPage {

  final case class MavenVersions(latest: String, release: Option[String], all: List[String])

  final case class PinSummary(
      tag: String,
      commit: String,
      repository: String,
      oracleSha256: String,
      attestation: String
  )
  final case class KindStats(treeKindCount: Int, tokenKindCount: Int)
  final case class CoverageStats(coveredCount: Int, fixtureCount: Int)
  final case class ReachabilityStats(
      reachableCount: Int,
      tokenReachableCount: Int,
      corpusFiles: Int,
      filesParsedWithoutError: Int,
      filesLosslessOfCleanlyParsed: Int
  )

  // ---------------------------------------------------------------- parsing

  def parsePin(path: Path): PinSummary = {
    val j = Json.parseFile(path)
    val up = j("upstream")
    PinSummary(
      tag = up("tag").asString,
      commit = up("commit").asString,
      repository = up("repository").asString,
      oracleSha256 = j("oracleArtifact")("sha256").asString,
      attestation = j("oracleArtifact")("attestation").asString
    )
  }

  def parseKindStats(treeKindPath: Path, tokenKindPath: Path): KindStats =
    KindStats(
      treeKindCount = Json.parseFile(treeKindPath)("treeKindCount").asInt,
      tokenKindCount = Json.parseFile(tokenKindPath)("tokenKindCount").asInt
    )

  def parseCoverage(path: Path): CoverageStats = {
    val j = Json.parseFile(path)
    CoverageStats(coveredCount = j("coveredCount").asInt, fixtureCount = j("fixtureCount").asInt)
  }

  def parseReachability(path: Path): ReachabilityStats = {
    val j = Json.parseFile(path)
    ReachabilityStats(
      reachableCount = j("reachableCount").asInt,
      tokenReachableCount = j("tokenReachableCount").asInt,
      corpusFiles = j("corpusFiles").asInt,
      filesParsedWithoutError = j("filesParsedWithoutError").asInt,
      filesLosslessOfCleanlyParsed = j("filesLosslessOfCleanlyParsed").asInt
    )
  }

  /** Real XML parsing via the JDK's own `javax.xml`, not regex over a structured format -- the same principle
    * `TreeKindExtractor` applies to Scala source, extended here. `maven-metadata.xml` is small and self-generated, but
    * "we control the format" is not a reason to parse it by hand.
    */
  def parseMavenMetadata(path: Path): MavenVersions = {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    val doc = factory.newDocumentBuilder().parse(path.toFile)
    def text(tag: String): Option[String] =
      Option(doc.getElementsByTagName(tag).item(0)).map(_.getTextContent)

    val versionNodes = doc.getElementsByTagName("version")
    val versions = (0 until versionNodes.getLength).map(versionNodes.item(_).getTextContent).toList

    MavenVersions(
      latest = text("latest").getOrElse(versions.headOption.getOrElse("")),
      release = text("release"),
      all = versions
    )
  }

  // --------------------------------------------------------------- escaping

  def escapeHtml(s: String): String =
    s.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;")

  // ---------------------------------------------------------------- render

  private val Styles: String =
    """<style>
      |  :root {
      |    color-scheme: light dark;
      |    --bg: #ffffff; --fg: #1a1a1a; --muted: #5b5b5b; --border: #e2e2e2;
      |    --card: #f7f7f7; --accent: #0969da; --code-bg: #f0f0f0;
      |    --badge-release-bg: #dafbe1; --badge-release-fg: #116329;
      |    --badge-snapshot-bg: #fff8c5; --badge-snapshot-fg: #7d5a00;
      |    --badge-build-bg: #eaeaea; --badge-build-fg: #444;
      |  }
      |  @media (prefers-color-scheme: dark) {
      |    :root {
      |      --bg: #0d1117; --fg: #e6edf3; --muted: #9198a1; --border: #30363d;
      |      --card: #161b22; --accent: #4493f8; --code-bg: #1c2128;
      |      --badge-release-bg: #133a24; --badge-release-fg: #56d364;
      |      --badge-snapshot-bg: #3d3211; --badge-snapshot-fg: #e3b341;
      |      --badge-build-bg: #21262d; --badge-build-fg: #c9d1d9;
      |    }
      |  }
      |  * { box-sizing: border-box; }
      |  body {
      |    margin: 0; padding: 0 1.25rem 4rem;
      |    background: var(--bg); color: var(--fg);
      |    font: 16px/1.55 -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
      |  }
      |  main { max-width: 46rem; margin: 0 auto; }
      |  header { padding: 3rem 0 1.5rem; border-bottom: 1px solid var(--border); margin-bottom: 2rem; }
      |  h1 { font-size: 1.9rem; margin: 0 0 0.4rem; }
      |  h2 { font-size: 1.2rem; margin: 2.5rem 0 0.75rem; padding-top: 0.5rem; border-top: 1px solid var(--border); }
      |  .tagline { color: var(--muted); font-size: 1.05rem; margin: 0; }
      |  .limitation {
      |    background: var(--card); border: 1px solid var(--border); border-radius: 8px;
      |    padding: 1rem 1.25rem; margin: 1.25rem 0; font-size: 0.95rem; color: var(--muted);
      |  }
      |  .limitation strong { color: var(--fg); }
      |  .stat-grid {
      |    display: grid; grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr));
      |    gap: 0.75rem; margin: 1rem 0;
      |  }
      |  .stat {
      |    background: var(--card); border: 1px solid var(--border); border-radius: 8px;
      |    padding: 0.85rem 1rem;
      |  }
      |  .stat .n { font-size: 1.5rem; font-weight: 600; display: block; }
      |  .stat .l { font-size: 0.8rem; color: var(--muted); }
      |  .note { font-size: 0.92rem; color: var(--muted); }
      |  table { width: 100%; border-collapse: collapse; margin: 0.5rem 0; font-size: 0.92rem; }
      |  th, td { text-align: left; padding: 0.5rem 0.6rem; border-bottom: 1px solid var(--border); }
      |  th { color: var(--muted); font-weight: 500; font-size: 0.82rem; text-transform: uppercase; letter-spacing: 0.02em; }
      |  code { background: var(--code-bg); padding: 0.15em 0.4em; border-radius: 4px; font-size: 0.9em; }
      |  a { color: var(--accent); }
      |  .badge { display: inline-block; padding: 0.1rem 0.55rem; border-radius: 999px; font-size: 0.78rem; font-weight: 500; }
      |  .badge-release { background: var(--badge-release-bg); color: var(--badge-release-fg); }
      |  .badge-snapshot { background: var(--badge-snapshot-bg); color: var(--badge-snapshot-fg); }
      |  .badge-build { background: var(--badge-build-bg); color: var(--badge-build-fg); }
      |  footer { margin-top: 3rem; padding-top: 1.5rem; border-top: 1px solid var(--border); color: var(--muted); font-size: 0.85rem; }
      |  pre { overflow-x: auto; background: var(--code-bg); padding: 0.9rem 1rem; border-radius: 8px; font-size: 0.85rem; }
      |  .overflow { overflow-x: auto; }
      |</style>""".stripMargin

  private def repoShortName(repository: String): String = repository.stripPrefix("https://github.com/")

  private def renderOverview(pin: PinSummary): String =
    s"""<header>
       |  <h1>flix-spec</h1>
       |  <p class="tagline">Shared test infrastructure for parsers of <a href="https://github.com/flix/flix">Flix</a></p>
       |</header>
       |
       |<p>
       |  A machine-readable inventory of the language's syntax tree and token kinds, a pinned corpus
       |  definition, and fixtures with expected tree shapes &mdash; all derived from the reference compiler at
       |  <a href="https://github.com/${repoShortName(
        pin.repository
      )}/releases/tag/${pin.tag}"><code>${pin.tag}</code></a>,
       |  and used by independent parser implementations to check that they agree with it.
       |</p>
       |
       |<div class="limitation">
       |  <strong>What this is not:</strong> a specification of Flix. It has no independent authority over
       |  the language. A derived suite cannot falsify the reference compiler &mdash; if Flix has a bug, this
       |  suite inherits it. Full rationale in
       |  <a href="https://github.com/wstein/flix-spec/blob/main/docs/PROJECTION.md">docs/PROJECTION.md</a>.
       |</div>""".stripMargin

  private def renderStats(kinds: KindStats, coverage: CoverageStats, reachability: ReachabilityStats): String =
    s"""<h2>What's in the pin</h2>
       |<div class="stat-grid">
       |  <div class="stat"><span class="n">${kinds.treeKindCount}</span><span class="l">TreeKinds</span></div>
       |  <div class="stat"><span class="n">${kinds.tokenKindCount}</span><span class="l">TokenKinds</span></div>
       |  <div class="stat"><span class="n">${coverage.fixtureCount}</span><span class="l">fixtures</span></div>
       |  <div class="stat"><span class="n">${reachability.corpusFiles}</span><span class="l">corpus files</span></div>
       |</div>
       |<p class="note">
       |  ${reachability.reachableCount}/${kinds.treeKindCount} TreeKinds and
       |  ${reachability.tokenReachableCount}/${kinds.tokenKindCount} TokenKinds are reachable somewhere in the
       |  ${reachability.corpusFiles}-file corpus (${reachability.filesParsedWithoutError} parse cleanly).
       |  Independently, ${coverage.coveredCount}/${kinds.treeKindCount} TreeKinds are exercised by a fixture.
       |  The two numbers come from separate generators &mdash; coverage from the fixture suite, reachability
       |  from a weekly corpus walk &mdash; and are not asserted equal here: a fixture can exercise a kind the
       |  ${reachability.corpusFiles}-file corpus never produces naturally.
       |  ${reachability.filesLosslessOfCleanlyParsed}/${reachability.filesParsedWithoutError} cleanly-parsed
       |  corpus files are <strong>lossless</strong> &mdash; concatenating every emitted token's text reproduces
       |  the source exactly, ignoring whitespace. This is the one property here that needs no oracle: it checks
       |  the parser against its own input, not against an expectation derived from it. Details in
       |  <a href="https://github.com/wstein/flix-spec/blob/main/docs/CONFORMANCE.md">docs/CONFORMANCE.md</a>.
       |</p>""".stripMargin

  private def renderOracleTable(pin: PinSummary): String =
    s"""<h2>Oracle</h2>
       |<table>
       |  <tr><th>Field</th><th>Value</th></tr>
       |  <tr><td>Upstream release</td><td><code>${pin.tag}</code></td></tr>
       |  <tr><td>Commit</td><td><code>${pin.commit.take(12)}&hellip;</code></td></tr>
       |  <tr><td>Oracle jar SHA-256</td><td><code>${pin.oracleSha256.take(16)}&hellip;</code></td></tr>
       |  <tr><td>Attestation</td><td>${escapeHtml(pin.attestation)}</td></tr>
       |</table>""".stripMargin

  private def versionBadge(version: String, release: Option[String]): String =
    if (version.endsWith("-SNAPSHOT")) "snapshot"
    else if (release.contains(version)) "release"
    else "build"

  private def renderVersions(mv: MavenVersions): String = {
    val sorted = mv.all.sorted.reverse
    val rows = sorted
      .map { v =>
        val kind = versionBadge(v, mv.release)
        s"""        <tr>
           |          <td><code>${escapeHtml(v)}</code></td>
           |          <td><span class="badge badge-$kind">$kind</span></td>
           |          <td><a href="maven/io/github/wstein/flix-spec/${escapeHtml(v)}/">browse</a></td>
           |        </tr>""".stripMargin
      }
      .mkString("\n")
    s"""<h2>Versions</h2>
       |<div class="overflow">
       |<table>
       |  <tr><th>Version</th><th></th><th></th></tr>
       |$rows
       |</table>
       |</div>
       |<p style="font-size: 0.85rem; color: var(--muted);">
       |  Read from this repository's own <code>maven-metadata.xml</code> &mdash; not maintained separately here.
       |</p>""".stripMargin
  }

  private def renderUsage(mv: MavenVersions): String = {
    val use = mv.release.getOrElse(mv.latest)
    s"""<h2>Use it</h2>
       |<pre><code>repositories {
       |    maven { url = uri("https://wstein.github.io/flix-spec/maven/") }
       |}
       |
       |dependencies {
       |    implementation("io.github.wstein:flix-spec:${escapeHtml(use)}")
       |}</code></pre>
       |<p style="font-size: 0.9rem;">
       |  See <a href="https://github.com/wstein/flix-spec/blob/main/docs/VERSIONING.md">docs/VERSIONING.md</a>
       |  &mdash; the version is plain semver and does not encode the Flix pin; a version can advertise a pin
       |  but never enforce one, so consumers assert <code>pin.json</code> directly.
       |</p>""".stripMargin
  }

  private def renderFooter(): String =
    """<footer>
      |  <a href="https://github.com/wstein/flix-spec">github.com/wstein/flix-spec</a> &middot; Apache-2.0 &middot;
      |  generated from <code>pin.json</code>, <code>ast/*.json</code> and <code>maven-metadata.xml</code> by
      |  <a href="https://github.com/wstein/flix-spec/blob/main/tools/project/src/main/scala/flix/spec/LandingPage.scala">LandingPage.scala</a>,
      |  never hand-edited
      |</footer>""".stripMargin

  def render(
      pin: PinSummary,
      kinds: KindStats,
      coverage: CoverageStats,
      reachability: ReachabilityStats,
      mavenVersions: MavenVersions
  ): String =
    s"""<!doctype html>
       |<html lang="en">
       |<head>
       |<meta charset="utf-8">
       |<meta name="viewport" content="width=device-width, initial-scale=1">
       |<title>flix-spec</title>
       |<meta name="description" content="Shared test infrastructure for parsers of Flix, derived from the reference compiler at a pinned release.">
       |$Styles
       |</head>
       |<body>
       |<main>
       |
       |${renderOverview(pin)}
       |
       |${renderStats(kinds, coverage, reachability)}
       |
       |${renderOracleTable(pin)}
       |
       |${renderVersions(mavenVersions)}
       |
       |${renderUsage(mavenVersions)}
       |
       |${renderFooter()}
       |
       |</main>
       |</body>
       |</html>
       |""".stripMargin

  // ------------------------------------------------------------------ main

  def main(args: Array[String]): Unit = {
    if (args.length != 2) {
      System.err.println("usage: LandingPage <maven-metadata.xml> <output index.html>")
      sys.exit(2)
    }
    val metadataPath = Paths.get(args(0))
    val outPath = Paths.get(args(1))
    require(Files.exists(metadataPath), s"FATAL: missing $metadataPath")

    val html = render(
      pin = parsePin(Paths.get("pin.json")),
      kinds = parseKindStats(Paths.get("ast/treekind.json"), Paths.get("ast/tokenkind.json")),
      coverage = parseCoverage(Paths.get("ast/coverage.json")),
      reachability = parseReachability(Paths.get("ast/reachability.json")),
      mavenVersions = parseMavenMetadata(metadataPath)
    )

    Option(outPath.getParent).foreach(Files.createDirectories(_))
    Files.writeString(outPath, html, StandardCharsets.UTF_8)
    println(s"Wrote $outPath")
  }
}
