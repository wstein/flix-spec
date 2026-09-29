package flix.spec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Offline, inert HTML derived exclusively from the versioned JSON report. No comparator lives here. */
object HtmlReport {
  def escape(text: String): String = text.flatMap {
    case '&'  => "&amp;"
    case '<'  => "&lt;"
    case '>'  => "&gt;"
    case '"'  => "&quot;"
    case '\'' => "&#39;"
    case c    => c.toString
  }

  private def scalar(value: Json): String = value match {
    case Json.JString(s) => s
    case Json.JNumber(n) => n.toString
    case Json.JBool(b)   => b.toString
    case Json.JNull      => "null"
    case _               => ""
  }

  private def valueHtml(value: Json): String = value match {
    case Json.JObject(fields) =>
      fields.toList
        .sortBy(_._1)
        .map { case (name, v) =>
          s"<dt>${escape(name)}</dt><dd>${valueHtml(v)}</dd>"
        }
        .mkString("<dl>", "", "</dl>")
    case Json.JArray(values) if values.isEmpty => "<span>none</span>"
    case Json.JArray(values) => values.map(v => s"<li>${valueHtml(v)}</li>").mkString("<ul>", "", "</ul>")
    case v                   => escape(scalar(v))
  }

  def render(report: Json): String = {
    Runner.checkVersion(report, "conformance-report", "report")
    val consumer = escape(report("consumer").asString)
    val lanes = List("oracle_conformance", "recovery_conformance", "diagnostic_conformance", "source_invariants")
      .map { name =>
        val lane = report("lanes")(name)
        val verdict = escape(lane("verdict").asString)
        val summary = List("claim", "caveat", "notApplicable", "failureReason").flatMap { key =>
          lane.get(key).map(v => s"<p><strong>${escape(key)}:</strong> ${escape(v.asString)}</p>")
        }.mkString
        val sample = lane
          .get("divergencesListed")
          .map { listed =>
            s"<p>Showing ${listed.asInt} of ${lane("divergenceCount").asInt} differences. " +
              "Listed differences may be a capped sample, not an accepted baseline.</p>"
          }
          .getOrElse("")
        val detail = Json.JObject(lane.asObject -- Set("verdict", "claim", "caveat", "notApplicable", "failureReason"))
        s"<section><h2>${escape(name)}: $verdict</h2>$summary$sample${valueHtml(detail)}</section>"
      }
      .mkString("\n")
    s"""<!doctype html>
       |<html lang="en"><head><meta charset="utf-8">
       |<meta name="viewport" content="width=device-width, initial-scale=1">
       |<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'">
       |<title>Conformance: $consumer</title>
       |<style>body{font:16px system-ui;max-width:75rem;margin:2rem auto;padding:0 1rem;line-height:1.5}
       |section{border-top:2px solid #888;margin-top:2rem}dt{font-weight:600}dd{margin-bottom:.5rem;overflow-wrap:anywhere}
       |li{margin-bottom:1rem}h1,h2{line-height:1.2}p{max-width:65rem}</style></head>
       |<body><h1>Conformance: $consumer</h1>
       |<p>Reported lane verdicts are independent; they are never combined into a score.
       |Use the runner exit status as the CI result: accepted-set and depth gates may add failures not represented here.</p>
       |<details><summary>Provenance</summary>${valueHtml(report("provenance"))}</details>
       |$lanes</body></html>
       |""".stripMargin
  }

  def write(input: Path, output: Path): Unit = {
    require(
      input.toAbsolutePath.normalize() != output.toAbsolutePath.normalize(),
      "HTML output must not overwrite its JSON input"
    )
    val doc = Json.parseFile(input)
    Runner.checkVersion(doc, "conformance-report", input.toString)
    val stream = getClass.getResourceAsStream("/conformance-report.schema.json")
    val schema =
      try Json.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
      finally stream.close()
    val errors = new SchemaValidator.Errors
    SchemaValidator.check(doc, schema, schema, input.toString, errors)
    require(errors.isEmpty, errors.toList.mkString("\n"))
    Option(output.getParent).foreach(Files.createDirectories(_))
    Files.writeString(output, render(doc), StandardCharsets.UTF_8)
  }
}
