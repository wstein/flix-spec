package flix.spec

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

/** Pins the normalisation rule itself.
  *
  * It was previously three verbatim copies of the same regex pair, none of them covered. The risk that creates is not
  * that one copy breaks loudly -- it is that one copy is *widened*, so the fixtures, the corpus run and a consumer's
  * report disagree about what "lossless" means while all three report success.
  */
@RunWith(classOf[JUnitRunner])
class TokenAccountingTest extends AnyFunSuite with Matchers {

  import TokenAccounting._

  test("whitespace is removed, everything else survives") {
    squeeze("def  f ( ) :\n  Unit = ()") shouldBe "deff():Unit=()"
    squeeze("\t a \r\n b ") shouldBe "ab"
  }

  test("the escape marker before a name is removed") {
    // Lexer.scala steps over the `$` explicitly, so the token spans `and` and the `$` belongs to no
    // token. Without this the six escaped-Java-name corpus files fail the invariant.
    squeeze("x.$and(y)") shouldBe "x.and(y)"
    squeeze("$abc") shouldBe "abc"
    squeeze("$_x") shouldBe "_x"
  }

  test("interpolation must still round-trip") {
    // The rule is narrow on purpose: `$` before `{` is content, not a marker. Widening it to every
    // `$` would silently stop catching a dropped interpolation sigil.
    squeeze("\"a${expr}b\"") shouldBe "\"a${expr}b\""
    squeeze("$1") shouldBe "$1"
  }

  test("whitespace is squeezed before the escape marker is considered") {
    // The two replacements are ordered, and the order is observable: `$ b` has no name character
    // immediately after the `$` until whitespace removal puts one there. Both sides of every real
    // comparison run through this same function, so the effect cancels -- but only because it is
    // one function. It is pinned here because a future reader reordering the two for tidiness would
    // change what the invariant accepts, silently and on one side only if the copies ever return.
    squeeze("a $ b") shouldBe "ab"
    squeeze("a $b") shouldBe "ab"
  }

  private def tree(json: String): Json = Json.parse(json)

  test("reconstruction concatenates token text in order") {
    val t = tree("""
      {"kind":"Root","children":[
        {"kind":"Decl.Def","children":[
          {"token":"KeywordDef","text":"def"},
          {"token":"Ident","text":"f"}
        ]},
        {"token":"ParenL","text":"("}
      ]}""")
    reconstruct(t) shouldBe "deff("
  }

  test("a tree with no token text is recognised as carrying none") {
    // A structural adapter emits kinds and no tokens, which docs/PROJECTION.md permits. Token
    // accounting cannot be evaluated on such output, and must say so rather than pass or fail it.
    carriesTokens(tree("""{"kind":"Root","children":[{"kind":"Decl.Def","children":[]}]}""")) shouldBe false
    carriesTokens(tree("""{"kind":"Root","children":[{"token":"Ident","text":"f"}]}""")) shouldBe true
  }

  test("a divergence report points at the first difference") {
    val report = describeDivergence("deff()", "defg()", window = 3)
    report should include("deff(")
    report should include("defg(")
    report should include("6 chars")
  }
}
