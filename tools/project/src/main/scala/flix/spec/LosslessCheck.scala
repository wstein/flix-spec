package flix.spec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

/** Asserts that every projected tree accounts for every non-whitespace character of its source.
  *
  * This is an **oracle-free** property, and there are very few of those here. A derived suite cannot falsify the
  * reference compiler -- if Flix has a bug, the expectations inherit it. But losslessness compares the tree against the
  * *input*, not against the reference, so it needs no independent specification and it can say something about the
  * reference itself. It joins reachability and determinism in that small set.
  *
  * The comparison is whitespace-insensitive by necessity: Flix does not emit whitespace as tokens, so byte-exact
  * reconstruction is impossible by construction. Everything else must survive -- including comment text and the
  * interior of string literals, which is why the check strips whitespace from both sides rather than trying to
  * re-insert it.
  *
  * What it catches, in any parser and not just this one: dropped tokens, duplicated tokens, and tokens whose recorded
  * text does not match the source they came from. A structural comparison cannot see any of those, because a tree can
  * be perfectly well-shaped and still have lost a token's contents.
  */
object LosslessCheck {

  def main(args: Array[String]): Unit = {
    val expectedDir = Paths.get("fixtures/expected")
    val files = Files
      .list(expectedDir)
      .iterator()
      .asScala
      .filter(_.getFileName.toString.endsWith(".json"))
      .toList
      .sortBy(_.toString)

    if (files.isEmpty) {
      System.err.println("FATAL: no expectations in fixtures/expected/")
      sys.exit(1)
    }

    var checked = 0
    var skipped = 0
    val failures = scala.collection.mutable.ListBuffer.empty[String]

    files.foreach { f =>
      val doc = Json.parseFile(f)
      doc("units").asArray.foreach { unit =>
        val source = Paths.get(unit("source").asString)
        if (!Files.exists(source)) {
          // Never silently pass over an input we could not read: a check that skips what it
          // cannot handle reports success it has not earned.
          skipped += 1
          failures += s"${f.getFileName}: source ${source} does not exist"
        } else {
          val fromTree = TokenAccounting.reconstruct(unit("tree"))
          val fromDisk = TokenAccounting.squeeze(Files.readString(source, StandardCharsets.UTF_8))
          checked += 1
          if (fromTree != fromDisk)
            failures +=
              s"${f.getFileName}: token text does not reconstruct ${unit("source").asString}\n" +
                TokenAccounting.describeDivergence(fromTree, fromDisk)
        }
      }
    }

    if (failures.nonEmpty) {
      System.err.println("FATAL: projected trees are not lossless")
      failures.foreach(e => System.err.println(s"  $e"))
      sys.exit(1)
    }

    println(
      s"OK: $checked projected tree(s) reconstruct their source exactly, ignoring whitespace" +
        (if (skipped > 0) s" ($skipped skipped)" else "")
    )
  }
}
