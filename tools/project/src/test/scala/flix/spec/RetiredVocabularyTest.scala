package flix.spec

import java.nio.file.{Path, Paths}
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

/** `ast/retired.json` is the record that survives a removal.
  *
  * An added kind announces itself: it appears in the inventory under a name a reader can look up. A removed one
  * announces nothing -- the digest stops matching, and a consumer holding a mapping onto it learns only that the
  * mapping never fires. Until this file existed the sole trace that `law` and `lawful` were ever keywords was two
  * `should not contain` lines in a test, which is a record that no consumer can read and no tool can join against.
  *
  * Being hand-maintained, it has to be falsifiable, and it is: a retired name must be absent from the inventory it was
  * retired from. If upstream ever brings one back, the claim here is wrong and this says so.
  */
@RunWith(classOf[JUnitRunner])
class RetiredVocabularyTest extends AnyFunSuite with Matchers {

  private val repoRoot: Path = Paths.get("../..").toAbsolutePath.normalize()

  private def names(file: String, key: String): Set[String] =
    Json.parseFile(repoRoot.resolve(file))(key).asArray.map(_("name").asString).toSet

  test("ast/retired.json conforms to its schema") {
    val doc = Json.parseFile(repoRoot.resolve("ast/retired.json"))
    val schema = Json.parseFile(repoRoot.resolve("schemas/retired.schema.json"))
    val errors = new SchemaValidator.Errors
    SchemaValidator.check(doc, schema, schema, "ast/retired.json", errors)
    errors.toList shouldBe Nil
  }

  test("every retired name really is absent from the vocabulary it was retired from") {
    val inventories = Map(
      "TreeKind" -> names("ast/treekind.json", "kinds"),
      "TokenKind" -> names("ast/tokenkind.json", "kinds"),
      "Annotation" -> names("ast/annotation.json", "annotations")
    )

    val retired = Json.parseFile(repoRoot.resolve("ast/retired.json"))("retired").asArray
    retired should not be empty

    retired.foreach { e =>
      val name = e("name").asString
      val vocabulary = e("vocabulary").asString
      withClue(
        s"'$name' is recorded as retired from $vocabulary at ${e("removedAtTag").asString}, " +
          "but the current inventory still defines it: "
      ) {
        inventories(vocabulary) should not contain name
      }
    }
  }
}
