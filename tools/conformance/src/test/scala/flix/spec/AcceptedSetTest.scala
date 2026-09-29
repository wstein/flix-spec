package flix.spec

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** The accepted set is the newest gate and was the least covered: it shipped with no test at all, and was then moved
  * between modules, so nothing would have reported it silently ceasing to apply.
  *
  * These assert the decisions rather than the printing -- what counts as new, what counts as resolved, and every way
  * the file is refused.
  */
@RunWith(classOf[JUnitRunner])
class AcceptedSetTest extends AnyFunSuite with Matchers {

  private val Revision = "648ce33ea86d074b1ac8911ddb50b19dd326a4a14f57669a13ea46811a19b7df"
  private val Consumer = "example-adapter"
  private val OneDiff = "hello.json|fixtures/positive/hello.flix.Root[0]|kind"
  private val OtherDiff = "trailing-dot.json|fixtures/negative/trailing-dot.flix.Root[0]|kind"

  private def withFile(json: String)(body: Path => Unit): Unit = {
    val f = Files.createTempFile("accepted", ".json")
    try {
      Files.writeString(f, json, StandardCharsets.UTF_8)
      body(f)
    } finally Files.deleteIfExists(f)
  }

  private def file(revision: String = Revision, consumer: Option[String] = None, lanes: String): String = {
    val c = consumer.map(x => s""""consumer": "$x",""").getOrElse("")
    s"""{"schemaVersion": 1, $c "fixtureRevision": "$revision", "lanes": {$lanes}}"""
  }

  test("a difference outside the set is new; one inside it is not") {
    withFile(file(lanes = s""""oracle_conformance": ["$OneDiff"]""")) { f =>
      val parsed = AcceptedSet.parse(f, Revision, Consumer).toOption.get

      AcceptedSet.verdict(parsed, "oracle_conformance", Set(OneDiff)).failed shouldBe false
      val v = AcceptedSet.verdict(parsed, "oracle_conformance", Set(OtherDiff))
      v.failed shouldBe true
      v.introduced shouldBe List(OtherDiff)
    }
  }

  test("an accepted difference that no longer occurs is reported, not removed") {
    withFile(file(lanes = s""""oracle_conformance": ["$OneDiff"]""")) { f =>
      val parsed = AcceptedSet.parse(f, Revision, Consumer).toOption.get
      val v = AcceptedSet.verdict(parsed, "oracle_conformance", Set.empty)
      v.resolved shouldBe List(OneDiff)
      // Resolved alone must not fail: deciding a difference is gone is the consumer's judgement, and it
      // belongs in the commit that fixed it rather than in this tool's output.
      v.failed shouldBe false
    }
  }

  test("counts do not net out: one fixed and one introduced still fails") {
    withFile(file(lanes = s""""oracle_conformance": ["$OneDiff"]""")) { f =>
      val parsed = AcceptedSet.parse(f, Revision, Consumer).toOption.get
      val v = AcceptedSet.verdict(parsed, "oracle_conformance", Set(OtherDiff))
      // Exactly the swap a count-based baseline cannot see: the total is unchanged at one.
      v.introduced shouldBe List(OtherDiff)
      v.resolved shouldBe List(OneDiff)
      v.failed shouldBe true
    }
  }

  test("a set recorded against a different fixture revision is refused") {
    withFile(file(revision = "0" * 64, lanes = s""""oracle_conformance": ["$OneDiff"]""")) { f =>
      AcceptedSet.parse(f, Revision, Consumer) match {
        case Left(p: AcceptedSet.RevisionMismatch) => p.message should include("re-confirmed")
        case other                                 => fail(s"expected a revision mismatch, got $other")
      }
    }
  }

  test("a set recorded by another consumer is refused") {
    withFile(file(consumer = Some("some-other-parser"), lanes = s""""oracle_conformance": ["$OneDiff"]""")) { f =>
      AcceptedSet.parse(f, Revision, Consumer) match {
        case Left(p: AcceptedSet.WrongConsumer) => p.message should include("some-other-parser")
        case other                              => fail(s"expected a wrong-consumer refusal, got $other")
      }
    }
  }

  test("a misspelled lane is refused rather than silently inert") {
    withFile(file(lanes = s""""oracle_conformanc": ["$OneDiff"]""")) { f =>
      AcceptedSet.parse(f, Revision, Consumer) match {
        case Left(p: AcceptedSet.UnknownLanes) => p.lanes shouldBe List("oracle_conformanc")
        case other                             => fail(s"expected an unknown-lane refusal, got $other")
      }
    }
  }

  test("source_invariants is not an acceptable lane") {
    withFile(file(lanes = """"source_invariants": ["whatever"]""")) { f =>
      AcceptedSet.parse(f, Revision, Consumer).isLeft shouldBe true
    }
  }

  test("a matching consumer is accepted, and omitting the consumer stays permissive") {
    withFile(file(consumer = Some(Consumer), lanes = s""""oracle_conformance": ["$OneDiff"]""")) { f =>
      AcceptedSet.parse(f, Revision, Consumer).isRight shouldBe true
    }
    withFile(file(lanes = s""""oracle_conformance": ["$OneDiff"]""")) { f =>
      AcceptedSet.parse(f, Revision, "any-consumer-at-all").isRight shouldBe true
    }
  }
}
