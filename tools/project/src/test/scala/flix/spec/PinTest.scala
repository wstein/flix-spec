package flix.spec

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import java.nio.file.{Path, Paths}

/** Asserts the one fact about `pin.json` that nothing derived from `pin.json` can establish: **the oracle is
  * `flix/flix`.**
  *
  * Every other gate in this repository checks the pin against itself. The jar's digest is compared to the digest
  * `pin.json` records; the vocabulary blobs are compared to the repository `pin.json` names; the fixtures are
  * regenerated from the jar `pin.json` points at. All of that is exactly as strong as the file, and a fork's jar hashes
  * to its own digest perfectly happily — so a `pin.json` naming a fork would sail through the entire suite, produce
  * fixtures describing a different compiler, and be contradicted by nothing.
  *
  * That is not hypothetical in this ecosystem. A committed lexicon here was once provenanced to a fork rather than to
  * the pin, and `docs/CONFORMANCE.md` records it. Text-scraping is usually blamed; the absence of this assertion is the
  * actual reason it could happen.
  *
  * So the upstream identity is hardcoded here, deliberately. It is an invariant, not configuration: a value read from
  * the file under test could never falsify that file. `tools/oracle/fetch.sh` refuses to download from anywhere else
  * and `tools/oracle/check-drift.sh` refuses to measure drift against anything else; this is the same rule where CI
  * runs it on every push, without a network call.
  */
@RunWith(classOf[JUnitRunner])
class PinTest extends AnyFunSuite with Matchers {

  private val repoRoot: Path = Paths.get("../..").toAbsolutePath.normalize()
  private def pin: Json = Json.parseFile(repoRoot.resolve("pin.json"))

  /** The oracle, always. Never a fork, however convenient one would be. */
  private val Upstream = "https://github.com/flix/flix"

  test("the pinned upstream is flix/flix") {
    pin("upstream")("repository").asString shouldBe Upstream
  }

  test("the oracle artifact comes from an upstream release") {
    // The digest check downstream proves the bytes are the bytes someone recorded. It cannot prove
    // whose bytes they were.
    val url = pin("oracleArtifact")("url").asString
    withClue(s"oracle artifact url '$url': ") {
      url should startWith(s"$Upstream/releases/download/")
    }
  }

  test("the artifact URL names the release the pin names") {
    // Bumping `upstream.tag` while leaving the artifact URL behind downloads the previous compiler
    // and verifies it against the previous digest, reporting success at every step.
    val tag = pin("upstream")("tag").asString
    val url = pin("oracleArtifact")("url").asString
    val named = url.stripPrefix(s"$Upstream/releases/download/").takeWhile(_ != '/')
    withClue(s"url names release '$named' but upstream.tag is '$tag': ")(named shouldBe tag)
  }

  test("every generated artifact was derived from the pinned commit") {
    // The join KindStatus performs at generation time, asserted again where it is cheap to run: a
    // stale artifact regenerated against a different oracle is how a suite starts describing two
    // compilers at once.
    val commit = pin("upstream")("commit").asString
    List(
      "ast/treekind.json",
      "ast/tokenkind.json",
      "ast/coverage.json",
      "ast/reachability.json",
      "ast/unattachable.json",
      "ast/transparency.json",
      "ast/status.json"
    ).foreach { p =>
      withClue(s"$p: ")(Json.parseFile(repoRoot.resolve(p))("upstreamCommit").asString shouldBe commit)
    }
  }

  test("the pin's identifiers are well-formed") {
    // pin.json has no schema, and these are the fields every other gate reads without checking.
    pin("upstream")("commit").asString should fullyMatch regex "[0-9a-f]{40}"
    pin("upstream")("treeHash").asString should fullyMatch regex "[0-9a-f]{40}"
    pin("oracleArtifact")("sha256").asString should fullyMatch regex "[0-9a-f]{64}"
    pin("upstream")("tag").asString should startWith("v")

    val blobs = pin("upstream")("vocabularySources").asObject
    withClue("vocabularySources must cover the parser and both vocabularies: ")(blobs.size should be >= 4)
    blobs.foreach { case (path, sha) =>
      withClue(s"$path: ")(sha.asString should fullyMatch regex "[0-9a-f]{40}")
    }
  }

  test("the committed fixtures were extracted against the pinned oracle") {
    val commit = pin("upstream")("commit").asString
    val sha = pin("oracleArtifact")("sha256").asString
    List(ProjectionExtractor.RawDir, ProjectionExtractor.NormalizedDir).foreach { dir =>
      val doc = Json.parseFile(repoRoot.resolve(s"$dir/hello.json"))
      withClue(s"$dir/hello.json: ") {
        doc("upstreamCommit").asString shouldBe commit
        doc("oracleSha256").asString shouldBe sha
      }
    }
  }
}
