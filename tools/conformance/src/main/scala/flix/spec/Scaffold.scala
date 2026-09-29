package flix.spec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

/** An integration starter, deliberately incapable of pretending to implement a parser. */
object Scaffold {
  def create(directory: Path, consumer: String): Unit = {
    require(
      consumer.matches("[A-Za-z0-9][A-Za-z0-9._-]*"),
      "consumer name must use letters, digits, dots, underscores or hyphens"
    )
    require(!Files.exists(directory), s"refusing to overwrite existing scaffold directory: $directory")
    val templates = List("README.md", "adapter.sh", "produce.sh", "check.sh", "projection-map.json")
      .map { name =>
        val stream = getClass.getResourceAsStream(s"/scaffold/$name")
        require(stream != null, s"missing scaffold template: $name")
        val text =
          try new String(stream.readAllBytes(), StandardCharsets.UTF_8)
          finally stream.close()
        name -> text.replace("@CONSUMER@", consumer)
      }
    Option(directory.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
    Files.createDirectory(directory)
    templates.foreach { case (name, text) =>
      val target = directory.resolve(name)
      Files.writeString(target, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
    }
    println(s"Created $directory. Implement adapter.sh and review projection-map.json before running check.sh.")
  }
}
