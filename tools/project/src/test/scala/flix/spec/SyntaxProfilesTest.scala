package flix.spec

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SyntaxProfilesTest extends AnyFunSuite with Matchers {
  private val root = Paths.get("../..").toAbsolutePath.normalize()

  test("ten exact release profiles validate independently of the current inventory") {
    SyntaxProfiles.validate(root) shouldBe 10
    SyntaxProfiles.select(root, "0.75.0")("tag").asString shouldBe "v0.75.0"
    intercept[IllegalArgumentException](SyntaxProfiles.select(root, "latest"))
  }

  private def mutated(change: Path => Unit)(check: IllegalArgumentException => Unit): Unit = {
    val copy = Files.createTempDirectory("syntax-profiles-mutation-")
    try {
      List("profiles", "schemas").foreach { dir =>
        val stream = Files.walk(root.resolve(dir))
        try
          stream.iterator().asScala.foreach { original =>
            val target = copy.resolve(root.relativize(original))
            if (Files.isDirectory(original)) Files.createDirectories(target)
            else Files.copy(original, target)
          }
        finally stream.close()
      }
      change(copy)
      check(intercept[IllegalArgumentException](SyntaxProfiles.validate(copy)))
    } finally {
      val stream = Files.walk(copy)
      try stream.iterator().asScala.toList.reverse.foreach(Files.delete)
      finally stream.close()
    }
  }
  private def edit(root: Path, path: String)(f: String => String): Unit = {
    val file = root.resolve(path)
    Files.writeString(file, f(Files.readString(file, UTF_8)), UTF_8)
  }
  private def render(value: Json): String = value match {
    case Json.JObject(fields) =>
      fields.toList.sortBy(_._1).map { case (k, v) => "\"" + Json.esc(k) + "\":" + render(v) }.mkString("{", ",", "}")
    case Json.JArray(items) => items.map(render).mkString("[", ",", "]")
    case Json.JString(s)    => "\"" + Json.esc(s) + "\""
    case Json.JNumber(n)    => n.toString
    case Json.JBool(b)      => b.toString
    case Json.JNull         => "null"
  }
  private def manifest(root: Path)(change: List[Json] => List[Json]): Unit = {
    val path = root.resolve("profiles/profiles.json")
    val doc = Json.parseFile(path)
    Files.writeString(
      path,
      render(Json.JObject(doc.asObject.updated("profiles", Json.JArray(change(doc("profiles").asArray))))),
      UTF_8
    )
  }
  private def snapshotMutation(root: Path)(change: String => String): Unit = {
    val path = "profiles/expected/0.75.0/law.json"
    val before = SyntaxProfiles.sha256(Files.readAllBytes(root.resolve(path)))
    edit(root, path)(change)
    val after = SyntaxProfiles.sha256(Files.readAllBytes(root.resolve(path)))
    edit(root, "profiles/profiles.json")(_.replace(before, after))
  }

  test("mutation: changed source bytes fail their source digest") {
    mutated(r => edit(r, "profiles/sources/law.flix")(_ + "\n"))(_.getMessage should include("digest mismatch"))
  }
  test("mutation: changed oracle expectation fails its digest") {
    mutated(r => edit(r, "profiles/expected/0.75.0/law.json")(_ + "\n"))(_.getMessage should include("digest mismatch"))
  }
  test("mutation: an eleventh release profile is rejected") {
    mutated(r => manifest(r)(entries => entries :+ entries.head))(
      _.getMessage should include("exact ten ordered release profiles")
    )
  }
  test("mutation: a missing source identity is rejected") {
    mutated(r =>
      manifest(r)(entries => {
        val first = entries.head
        val sources = first("sources").asObject - "main/src/ca/uwaterloo/flix/language/ast/SyntaxTree.scala"
        Json.JObject(first.asObject.updated("sources", Json.JObject(sources))) :: entries.tail
      })
    )(
      _.getMessage should include("exact four vocabulary source identities")
    )
  }
  test("mutation: an extra source identity is rejected") {
    mutated(r =>
      edit(r, "profiles/profiles.json")(
        _.replace("\"sources\": {", "\"sources\": {\"extra.scala\": \"0000000000000000000000000000000000000000\",")
      )
    )(
      _.getMessage should include("exact four vocabulary source identities")
    )
  }
  test("mutation: malformed source blob identities are rejected") {
    mutated(r =>
      manifest(r)(entries => {
        val first = entries.head
        val key = "main/src/ca/uwaterloo/flix/language/ast/SyntaxTree.scala"
        val sources = first("sources").asObject.updated(key, Json.JString("not-a-blob"))
        Json.JObject(first.asObject.updated("sources", Json.JObject(sources))) :: entries.tail
      })
    )(_.getMessage should include("invalid source blob identity"))
  }
  test("mutation: cross-profile snapshots cannot replace a historical expectation") {
    mutated(r =>
      edit(r, "profiles/profiles.json")(
        _.replace("profiles/expected/0.75.0/law.json", "profiles/expected/0.75.1/law.json")
      )
    )(
      _.getMessage should include("cross-profile expectation")
    )
  }
  test("artifact digests reject a substituted jar before executing it") {
    val file = Files.createTempFile("syntax-profile-not-an-oracle-", ".jar")
    try {
      intercept[IllegalArgumentException](
        SyntaxProfiles.probe(root, "0.75.0", file, root.resolve("profiles/sources/law.flix"))
      ).getMessage should include("digest mismatch")
    } finally Files.delete(file)
  }
  test("mutation: a re-digested snapshot cannot lie about acceptance") {
    mutated(r => snapshotMutation(r)(_.replace("\"accepted\":true", "\"accepted\":false")))(
      _.getMessage should include("inconsistent acceptance")
    )
  }
  test("mutation: a re-digested malformed historical token fails its own schema") {
    mutated(r =>
      snapshotMutation(r)(_.replace("\"token\":\"KeywordTrait\",\"text\":\"trait\"", "\"token\":\"KeywordTrait\""))
    )(
      _.getMessage should include("required")
    )
  }
  test("mutation: a re-digested non-root tree is rejected") {
    mutated(r => snapshotMutation(r)(_.replace("\"kind\":\"Root\"", "\"kind\":\"LostRoot\"")))(
      _.getMessage should include("measured nonempty Root")
    )
  }
  test("mutation: a re-digested empty oracle tree is rejected") {
    mutated(r =>
      snapshotMutation(r)(_ => """{"accepted":true,"diagnostics":[],"tree":{"kind":"Root","children":[]}}""")
    )(
      _.getMessage should include("empty oracle tree")
    )
  }
  test("measured law removal is version bound") {
    def accepted(version: String, id: String): Json =
      Json.parseFile(root.resolve(s"profiles/expected/$version/$id.json"))("accepted")
    accepted("0.75.1", "law") shouldBe Json.JBool(true)
    accepted("0.75.2", "law") shouldBe Json.JBool(false)
    accepted("0.75.1", "lawful") shouldBe Json.JBool(true)
    accepted("0.75.2", "lawful") shouldBe Json.JBool(false)
  }
  test("every historical parser reports both lexer and parser sentinel failures") {
    SyntaxProfiles.Versions.foreach { version =>
      def diagnostics(id: String): List[String] =
        Json.parseFile(root.resolve(s"profiles/expected/$version/$id.json"))("diagnostics").asArray.map(_.asString)
      diagnostics("invalid-lexer") shouldBe List("UnexpectedToken", "UnterminatedString")
      diagnostics("invalid-parser") shouldBe List("Malformed")
      val lexical = Json.parseFile(root.resolve(s"profiles/expected/$version/invalid-lexer.json"))
      names(lexical, "token") should contain("Err")
      names(lexical, "token").exists(_.contains("/")) shouldBe false
    }
  }
  test("measured effect parameters and extensible tails have explicit release boundaries") {
    SyntaxProfiles.Versions.foreach { version =>
      def accepted(id: String): Json = Json.parseFile(root.resolve(s"profiles/expected/$version/$id.json"))("accepted")
      accepted("effect-type-parameter") shouldBe Json.JBool(!version.startsWith("0.75."))
      accepted("complex-row-tail") shouldBe Json.JBool(Set("0.78.0", "0.79.0").contains(version))
    }
  }
  private def names(value: Json, key: String): List[String] = value match {
    case Json.JObject(fields) => fields.get(key).toList.map(_.asString) ++ fields.values.toList.flatMap(names(_, key))
    case Json.JArray(items)   => items.flatMap(names(_, key))
    case _                    => Nil
  }
  test("tight token splitting starts at 0.76.2 and comments change at 0.79.0") {
    SyntaxProfiles.Versions.foreach { version =>
      val tight = Json.parseFile(root.resolve(s"profiles/expected/$version/tight-use.json"))
      val expected =
        if (version.startsWith("0.75.") || Set("0.76.0", "0.76.1").contains(version)) "ColonColon"
        else "ColonColonTight"
      names(tight, "token").filter(_.startsWith("ColonColon")) shouldBe List(expected)
      val comments = Json.parseFile(root.resolve(s"profiles/expected/$version/comments.json"))
      if (version == "0.79.0") {
        names(comments, "kind") should contain("DocComment")
        names(comments, "kind") should contain("PlainComment")
        names(comments, "kind") should not contain "CommentList"
      } else names(comments, "kind") should contain("CommentList")
    }
  }
}
