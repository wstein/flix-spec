package flix.spec

import java.nio.file.{Files, Paths}
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class Pin079BehaviorTest extends AnyFunSuite with Matchers {
  private def parse(text: String): ProjectionExtractor.Projection = {
    val root = Paths.get("../..").toAbsolutePath.normalize()
    val file = Files.createTempFile(root, "pin079-", ".flix")
    try {
      Files.writeString(file, text)
      ProjectionExtractor.project(file, root)
    } finally Files.delete(file)
  }

  test("all three open predicate types accept compound tails") {
    List("#| A(Int32) | List[r] |#", "#{ A(Int32) | List[r] }", "#( A(Int32) | List[r] )").foreach { tail =>
      withClue(tail) {
        val result = parse(s"type alias T[r] = $tail\n")
        result.diagnostics shouldBe empty
        result.tree should include("\"kind\":\"Type.Apply\"")
      }
    }
  }

  test("comments do not interrupt a qualified name or expression lookahead") {
    val result = parse("def f(): Int32 = Foo/* between path segments */.bar(1 /* left */ + /* right */ 2)\n")
    result.diagnostics shouldBe empty
    result.tree should include("\"kind\":\"PlainComment\"")
    result.tree should include("\"kind\":\"Expr.Binary\"")
  }

  test("comments do not remove the lexer's whitespace-before-dot restriction") {
    val result = parse("def f(): Int32 = Foo /* comment */ .bar(1)\n")
    result.diagnostics.map(_.kind) should contain("FreeDot")
  }

  test("a leading doc belongs to its declaration and is no longer a Doc wrapper") {
    val result = parse("/// documented\ndef f(): Int32 = 1\n")
    result.diagnostics shouldBe empty
    result.tree should include("\"kind\":\"DocComment\"")
    result.tree should not include "\"kind\":\"Doc\""
  }

  test("an inline or orphaned doc is diagnosed as misplaced") {
    List("def f(): Int32 = 1 /// trailing\n", "/// orphan\n").foreach { source =>
      val result = parse(source)
      result.diagnostics.map(_.kind) should contain("MisplacedDocComments")
      result.tree should include("\"kind\":\"PlainComment\"")
    }
  }
}
