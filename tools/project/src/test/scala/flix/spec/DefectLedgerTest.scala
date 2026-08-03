package flix.spec

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import java.nio.file.{Files, Path, Paths}
import java.time.LocalDate

/** Structural guards on `defects/ledger.json`.
  *
  * The reproducer assertions themselves are re-run by `validateDefects` inside `verify.sh`, because they need the
  * pinned oracle jar to parse anything. What is checked here is everything that does not: the shape of the ledger, the
  * consistency the schema cannot express, and the promise that the files an entry points at exist.
  */
@RunWith(classOf[JUnitRunner])
class DefectLedgerTest extends AnyFunSuite with Matchers {

  private val repoRoot: Path = Paths.get("../..").toAbsolutePath.normalize()
  private val ledger = Json.parseFile(repoRoot.resolve("defects/ledger.json"))
  private val entries = ledger("entries").asArray

  test("the ledger conforms to its schema") {
    val schema = Json.parseFile(repoRoot.resolve("schemas/defect-ledger.schema.json"))
    val errors = new SchemaValidator.Errors
    SchemaValidator.check(ledger, schema, schema, "ledger.json", errors)
    withClue(errors.toList.mkString("; "))(errors.isEmpty shouldBe true)
  }

  test("ids are unique and sorted") {
    // Ids are cited by consumers, so they are never reused and never renumbered.
    val ids = entries.map(_("id").asString)
    ids.distinct.length shouldBe ids.length
    ids shouldBe ids.sorted
  }

  test("every reproducer exists and is a Flix source file") {
    entries.foreach { e =>
      val repro = repoRoot.resolve(e("reproducer").asString)
      withClue(s"${e("id").asString}: ") {
        Files.isRegularFile(repro) shouldBe true
        Files.readString(repro).trim should not be empty
      }
    }
  }

  test("an entry asserts something falsifiable") {
    // An entry whose assertion names no kinds cannot fail when upstream fixes the defect, which
    // would make it exactly the folklore the ledger exists to replace.
    entries.foreach { e =>
      val a = e("assert")
      val absent = a.get("absentKinds").map(_.asArray.length).getOrElse(0)
      val present = a.get("presentKinds").map(_.asArray.length).getOrElse(0)
      withClue(s"${e("id").asString} asserts no kinds: ")((absent + present) should be > 0)
    }
  }

  test("upstream status and issue link agree") {
    entries.foreach { e =>
      val status = e("upstreamStatus").asString
      val issue = e.get("upstreamIssue").filterNot(_.isNull).map(_.asString)
      withClue(s"${e("id").asString}: ") {
        status match {
          case "filed"     => issue.getOrElse(fail("filed but no issue URL")) should startWith("https://")
          case "not-filed" => issue shouldBe None
          case other       => fail(s"unknown upstreamStatus '$other'")
        }
      }
    }
  }

  test("no entry has already expired") {
    // The same gate validateDefects applies, asserted here so the failure names the ledger rather
    // than surfacing partway through the end-to-end suite.
    val today = LocalDate.now()
    entries.foreach { e =>
      val due = LocalDate.parse(e("review").asString)
      withClue(s"${e("id").asString} was due for re-triage on $due: ")(due.isBefore(today) shouldBe false)
    }
  }

  test("citations point at reference sources with a line") {
    entries.foreach { e =>
      val citations = e("citations").asArray.map(_.asString)
      withClue(s"${e("id").asString}: ")(citations should not be empty)
      citations.foreach(c =>
        withClue(s"${e("id").asString} citation '$c': ")(c should fullyMatch regex """.+\.scala:\d+""")
      )
    }
  }

  test("the ledger's generated block lists every entry") {
    val doc = Files.readString(repoRoot.resolve("docs/DEFECTS.md"))
    val body = doc.substring(
      doc.indexOf("<!-- generated: defects -->"),
      doc.indexOf("<!-- /generated: defects -->")
    )
    entries.foreach(e =>
      withClue(s"${e("id").asString} missing from docs/DEFECTS.md: ")(body should include(e("id").asString))
    )
  }
}
