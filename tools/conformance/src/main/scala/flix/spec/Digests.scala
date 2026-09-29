package flix.spec

import java.nio.file.{Files, Path}
import java.security.MessageDigest

/** Byte-level identity helpers, with no dependency on oracle extraction. */
object Digests {
  def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

  def fileDigest(path: Path): String = sha256Hex(Files.readAllBytes(path))
}
