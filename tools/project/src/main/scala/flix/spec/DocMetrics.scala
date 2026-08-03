package flix.spec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** Rewrites the marker-delimited blocks in `README.md` and `docs/CONFORMANCE.md` from `ast&#47;*.json` and `pin.json`.
  *
  * Every count in this repository is already generated. The counts *about* those counts were not: they were retyped
  * into prose, and prose is the one artifact `verify.yml`'s `git diff --exit-code` never checked. That gap is not
  * hypothetical -- at the time this was written the README claimed 134 fixtures against 136 on disk, 184 covered kinds
  * against 186, and asserted "coverage equals reachability" some while after it had stopped being true.
  * `CONFORMANCE.md` managed to go stale (476 of 4181 nodes, against 483 of 4228) inside the very paragraph explaining
  * that the figure is generated and instructing the reader to "read the artifact, not this sentence".
  *
  * So the sentence is now the artifact. A block between `&lt;!-- generated: name --&gt;` and
  * `&lt;!-- /generated: name --&gt;` belongs to this generator; edit the surrounding prose freely, but anything inside
  * a block is overwritten. CI regenerates and diffs, so a stale number fails the build exactly like a stale
  * `ast/treekind.json` already does.
  *
  * Deliberately narrow: only figures that are mechanically derivable go in blocks. The *arguments* around them -- why
  * `Eof` is a sentinel, why transparency has to be symmetric -- stay hand-written, because a generator has nothing to
  * say about them and a doc made entirely of tables would be worse than the staleness it fixes.
  */
object DocMetrics {

  private val Begin = (n: String) => s"<!-- generated: $n -->"
  private val End = (n: String) => s"<!-- /generated: $n -->"

  /** Replaces the body between the markers for `name`, leaving the markers themselves in place. */
  private def splice(path: Path, name: String, body: String): Boolean = {
    val text = Files.readString(path, StandardCharsets.UTF_8)
    val begin = Begin(name)
    val end = End(name)
    val b = text.indexOf(begin)
    val e = text.indexOf(end)
    if (b < 0 || e < 0 || e < b) {
      System.err.println(s"FATAL: $path is missing a well-formed '$name' block.")
      System.err.println(s"  Expected $begin ... $end")
      sys.exit(1)
    }
    if (text.indexOf(begin, b + 1) >= 0) {
      System.err.println(s"FATAL: $path declares the '$name' block more than once.")
      sys.exit(1)
    }
    val updated = text.substring(0, b + begin.length) + "\n" + body.stripLineEnd + "\n" + text.substring(e)
    if (updated == text) false
    else {
      Files.writeString(path, updated, StandardCharsets.UTF_8)
      true
    }
  }

  private def row(label: String, tree: String, token: String): String = s"| $label | $tree | $token |"

  def main(args: Array[String]): Unit = {
    val pin = Json.parseFile(Paths.get("pin.json"))
    val status = Json.parseFile(Paths.get("ast/status.json"))
    val coverage = Json.parseFile(Paths.get("ast/coverage.json"))
    val reach = Json.parseFile(Paths.get("ast/reachability.json"))

    val tag = pin("upstream")("tag").asString
    val commit = pin("upstream")("commit").asString.take(8)
    val fixtures = coverage("fixtureCount").asInt
    val corpusFiles = reach("corpusFiles").asInt
    val clean = reach("filesParsedWithoutError").asInt
    val lossless = reach("filesLosslessOfCleanlyParsed").asInt

    val tree = status("treeKind")
    val token = status("tokenKind")
    def both(field: String): (String, String) = (tree(field).asInt.toString, token(field).asInt.toString)

    val (treeTotal, tokTotal) = both("total")
    val (treeRc, tokRc) = both("reachable-covered")
    val (treeFo, tokFo) = both("fixture-only")
    val (treeCo, tokCo) = both("corpus-only")
    val (treeUn, tokUn) = both("structurally-unattachable")
    val (treeUk, tokUk) = both("unknown")

    val statusBlock =
      // Every line needs the `|` margin marker, including the first -- and these lines *begin* with a table `|`, so
      // each row carries two: one stripMargin eats, one Markdown keeps.
      s"""|| Status | `TreeKind` | `TokenKind` |
         || --- | ---: | ---: |
         |${row("Inventory", treeTotal, tokTotal)}
         |${row("`reachable-covered` — the corpus emits it, a fixture pins it", treeRc, tokRc)}
         |${row("`fixture-only` — only a curated fixture reaches it", treeFo, tokFo)}
         |${row("`corpus-only` — real Flix reaches it, no fixture does", treeCo, tokCo)}
         |${row(
           "`structurally-unattachable` — cannot appear in any tree, argued in `ast/unattachable.json`",
           treeUn,
           tokUn
         )}
         |${row("`unknown` — neither exercised nor explained", treeUk, tokUk)}
         |
         |Measured over $fixtures fixtures and $corpusFiles corpus files at pin `$tag` (`$commit`).
         |`corpus-only` is the only row that is a to-do list. Machine-readable form:
         |[`ast/status.json`](ast/status.json).""".stripMargin

    val nodeCount = coverage("nodeCount").asInt
    val wrapperNodes = coverage("singleChildWrapperNodes").asInt
    val wrapPct = f"${100.0 * wrapperNodes / nodeCount}%.1f"
    val wrapperBlock =
      s"""At this pin that is **$wrapperNodes of $nodeCount nodes ($wrapPct%)**, across $fixtures fixtures.
         |Read [`ast/coverage.json`](../ast/coverage.json) for the per-kind `alwaysSingleChildWrapper`
         |breakdown; this paragraph is regenerated from it rather than retyped.""".stripMargin

    val losslessBlock =
      // "870 of 870" reads as a coincidence rather than a clean sweep, so say so plainly when it is one.
      s"""It now holds on **all $fixtures fixtures and ${if (lossless == clean) s"all $clean"
        else s"$lossless of $clean"}
         |cleanly-parsed corpus files** — $clean of the $corpusFiles corpus files parse without error, and the
         |remainder are excluded rather than failing (see below).""".stripMargin

    val edits = List(
      (Paths.get("README.md"), "status", statusBlock),
      (Paths.get("docs/CONFORMANCE.md"), "wrappers", wrapperBlock),
      (Paths.get("docs/CONFORMANCE.md"), "lossless", losslessBlock)
    ).map { case (p, n, b) => (p, n, splice(p, n, b)) }

    edits.foreach { case (p, n, changed) => println(s"${if (changed) "updated" else "unchanged"}  $p [$n]") }
    val changed = edits.count(_._3)
    println(s"generateDocs: ${edits.length} block(s) checked, $changed rewritten")
  }
}
