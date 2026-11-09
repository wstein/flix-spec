package flix.spec

import java.nio.file.{Files, Path, Paths}
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner
import scala.jdk.CollectionConverters._

@RunWith(classOf[JUnitRunner])
class ReleaseBundleTest extends AnyFunSuite with Matchers {
  private val root = Paths.get("../..").toAbsolutePath.normalize()
  private def run(args: String*): Int = new ProcessBuilder(args: _*).inheritIO().start().waitFor()

  private def stage(): Path = {
    val target = Files.createTempDirectory("release-bundle-stage-")
    val source = root.resolve("packaging/build/assembled")
    val walk = Files.walk(source)
    try
      walk.iterator().asScala.foreach { file =>
        val dest = target.resolve(source.relativize(file))
        if (Files.isDirectory(file)) Files.createDirectories(dest) else Files.copy(file, dest)
      }
    finally walk.close()
    target
  }

  private def bundle(stage: Path, output: Path): (Int, String) = {
    val process = new ProcessBuilder(
      "bash",
      root.resolve("tools/packaging/assemble-release-bundle.sh").toString,
      stage.toString,
      root.resolve("README.md").toString,
      output.toString,
      "v0.79.0"
    )
      .redirectErrorStream(true)
      .start()
    val text = new String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    (process.waitFor(), text)
  }

  test("release tar preserves usable profile selection, validation and both fixture forms") {
    val output = Files.createTempDirectory("release-bundle-test-")
    bundle(stage(), output)._1 shouldBe 0
    val unpacked = Files.createTempDirectory("release-bundle-unpacked-")
    run("tar", "-xzf", output.resolve("flix-spec-0.79.0.tar.gz").toString, "-C", unpacked.toString) shouldBe 0
    val data = unpacked.resolve("flix-spec-0.79.0")
    List("fixtures/raw", "fixtures/expected", "ast/transparency.json").foreach(p =>
      Files.exists(data.resolve(p)) shouldBe true
    )
    SyntaxProfiles.validate(data) shouldBe 10
    SyntaxProfiles.Versions.foreach(v => SyntaxProfiles.select(data, v)("version").asString shouldBe v)
    SyntaxProfiles.Versions.map(v => SyntaxProfiles.select(data, v)("cases").asArray.size).sum shouldBe 90
  }

  test("omitting profiles from staged data fails release assembly rather than shipping a partial bundle") {
    val staged = stage()
    val walk = Files.walk(staged.resolve("profiles"))
    try walk.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach(Files.delete(_))
    finally walk.close()
    val (exit, message) = bundle(staged, Files.createTempDirectory("release-bundle-mutation-"))
    exit should not be 0
    message should include("FATAL: release bundle is missing profiles/profiles.json")
  }
}
