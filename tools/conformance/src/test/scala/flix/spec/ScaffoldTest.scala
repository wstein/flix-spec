package flix.spec

import java.nio.file.Files
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ScaffoldTest extends AnyFunSuite {
  test("starter is consumer-owned, has no accepted differences and never overwrites") {
    val parent = Files.createTempDirectory("scaffold-test")
    val target = parent.resolve("consumer")
    try {
      Scaffold.create(target, "my-parser")
      val map = Json.parseFile(target.resolve("projection-map.json"))
      assert(map("consumer").asString == "my-parser")
      assert(map("mappings").asObject.isEmpty)
      assert(map("capabilities").asArray.isEmpty)
      assert(!Files.exists(target.resolve("accepted.json")))
      intercept[IllegalArgumentException] { Scaffold.create(target, "other") }
      assert(Json.parseFile(target.resolve("projection-map.json")) == map)
      assert(Files.readString(target.resolve("adapter.sh")).contains("exit 2"))
    } finally {
      List("README.md", "adapter.sh", "produce.sh", "check.sh", "projection-map.json").foreach(n =>
        Files.deleteIfExists(target.resolve(n))
      )
      Files.deleteIfExists(target)
      Files.delete(parent)
    }
  }

  test("invalid names fail before creating any output") {
    val parent = Files.createTempDirectory("scaffold-name")
    val target = parent.resolve("consumer")
    try {
      intercept[IllegalArgumentException] { Scaffold.create(target, "$(unsafe)") }
      assert(!Files.exists(target))
    } finally Files.delete(parent)
  }
}
