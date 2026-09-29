package flix.spec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner
import org.junit.runner.RunWith

import Json._

@RunWith(classOf[JUnitRunner])
class JsonTest extends AnyFunSuite with Matchers {

  test("parses scalars") {
    Json.parse("\"hi\"") shouldBe JString("hi")
    Json.parse("42") shouldBe JNumber(BigDecimal(42))
    Json.parse("-3.5") shouldBe JNumber(BigDecimal(-3.5))
    Json.parse("true") shouldBe JBool(true)
    Json.parse("false") shouldBe JBool(false)
    Json.parse("null") shouldBe JNull
  }

  test("parses nested objects and arrays") {
    val doc = Json.parse("""{"a": 1, "b": [1, 2, {"c": "d"}], "e": {}}""")
    doc("a").asInt shouldBe 1
    doc("b").asArray.map(_.getClass.getSimpleName) should have length 3
    doc("b").asArray(2)("c").asString shouldBe "d"
    doc("e").asObject shouldBe Map.empty
  }

  test("unescapes string content") {
    Json.parse(""""a\"b\\c\ndA"""") shouldBe JString("a\"b\\c\nd" + "A")
  }

  test("apply and get return JNull/None for missing keys, not an exception") {
    val doc = Json.parse("""{"a": 1}""")
    doc("missing") shouldBe JNull
    doc.get("missing") shouldBe None
  }

  test("asString/asInt/asArray/asObject fail loudly on the wrong shape") {
    an[Json.JsonException] should be thrownBy Json.parse("1").asString
    an[Json.JsonException] should be thrownBy Json.parse("\"x\"").asInt
    an[Json.JsonException] should be thrownBy Json.parse("1").asArray
    an[Json.JsonException] should be thrownBy Json.parse("1").asObject
  }

  test("rejects trailing content and truncated input") {
    an[Json.JsonException] should be thrownBy Json.parse("{} garbage")
    an[Json.JsonException] should be thrownBy Json.parse("""{"a": """)
  }

  test("round-trips a realistic projected-tree fragment") {
    val text =
      """{"kind":"Expr.Apply","span":{"start":{"line":1,"col":1},"end":{"line":1,"col":5}},
        |"children":[{"token":"KeywordDef","text":"def","start":{"line":1,"col":1},"end":{"line":1,"col":4}}]}""".stripMargin
    val doc = Json.parse(text)
    doc("kind").asString shouldBe "Expr.Apply"
    doc("span")("start")("line").asInt shouldBe 1
    doc("children").asArray.head("token").asString shouldBe "KeywordDef"
  }
}
